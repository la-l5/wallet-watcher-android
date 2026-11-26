package com.walletwatcher.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max

class LedgerEngine(
    val store: LedgerStore,
    private val isNetworkAvailable: () -> Boolean = { true },
    var dedupWindowMs: Long = 10_000L,
    var mirrorWindowMs: Long = 180_000L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val parse: (AiConfig, UserProfile, MergedPayload) -> AiParseResult = AiClient::parse
) {
    private sealed class DeferredEvent {
        data class Notif(val raw: RawNotification) : DeferredEvent()
        data class Screen(val capture: ScreenBillCapture) : DeferredEvent()
    }

    private enum class BackfillResult {
        ALREADY_EXISTS,
        BACKFILLED,
        AMBIGUOUS,
        NO_MATCH
    }

    // Strict lock hierarchy: replayLock -> finalizeLock -> bufferLock
    private val replayLock = Any()
    private val finalizeLock = Any()
    private val bufferLock = Any()
    private val buffers = LinkedHashMap<String, BufferedGroup>()
    private val delivered = LinkedHashMap<Pair<String, String>, Pair<Long, Pair<String, String>>>()
    private val deferredDuringRestore = mutableListOf<DeferredEvent>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var restoring: Boolean = false

    @Volatile
    var profile: UserProfile = store.loadProfile()
        private set

    @Volatile
    var aiConfig: AiConfig = store.loadAiConfig()
        private set

    fun addOnChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeOnChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in changeListeners) {
            runCatching { l() }
        }
    }

    fun updateProfile(newProfile: UserProfile) {
        profile = newProfile
        store.saveProfile(newProfile)
        store.addLog("CONFIG", "已更新本地自己人档案（仅保存在此设备）")
        notifyChanged()
    }

    fun updateAiConfig(newConfig: AiConfig, persistConfig: AiConfig = newConfig) {
        aiConfig = newConfig
        store.saveAiConfig(persistConfig)
        store.addLog("CONFIG", "AI 引擎已更新为：${newConfig.displayLabel}")
        notifyChanged()
    }

    /**
     * Pauses ingestion, flushing, and queue replay while an atomic database restore executes.
     * Events arriving mid-restore preserve their original type and are ALWAYS replayed after the
     * restore outcome is settled (both on success and on failure/rollback).
     */
    fun <T> withRestoreBarrier(block: () -> T): T {
        val pendingAfterRestore: List<DeferredEvent>
        var result: T? = null
        var failure: Throwable? = null
        synchronized(replayLock) {
            synchronized(finalizeLock) {
                synchronized(bufferLock) {
                    restoring = true
                }
                try {
                    result = block()
                    synchronized(bufferLock) {
                        buffers.clear()
                        delivered.clear()
                    }
                    profile = store.loadProfile()
                    aiConfig = store.loadAiConfig()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    synchronized(bufferLock) {
                        restoring = false
                        pendingAfterRestore = deferredDuringRestore.toList()
                        deferredDuringRestore.clear()
                    }
                }
            }
        }
        var failedReplays = 0
        var lastReplayError: String? = null
        for (ev in pendingAfterRestore) {
            val replayRes = runCatching {
                when (ev) {
                    is DeferredEvent.Notif -> ingestNotification(ev.raw)
                    is DeferredEvent.Screen -> ingestScreenCapture(ev.capture)
                }
            }
            if (replayRes.isFailure) {
                failedReplays++
                lastReplayError = replayRes.exceptionOrNull()?.javaClass?.simpleName ?: "UnknownError"
            }
        }
        if (failedReplays > 0) {
            runCatching {
                store.addLog(
                    "RESTORE_REPLAY_ERR",
                    "恢复屏障结束后有 $failedReplays 条延迟事件重放失败（最后异常：$lastReplayError）"
                )
            }
        }
        notifyChanged()
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun ingestNotification(notif: RawNotification) {
        synchronized(bufferLock) {
            if (restoring) {
                deferredDuringRestore.add(DeferredEvent.Notif(notif))
                return
            }
        }
        if (notif.isGroupSummary && notif.subMessages.isNotEmpty()) {
            store.addLog("UNPACK", "展开折叠通知（${notif.appName}，共 ${notif.subMessages.size} 条子消息）")
            notif.subMessages.forEachIndexed { idx, line ->
                ingestSingle(
                    notif.copy(
                        content = line,
                        timestampMs = notif.timestampMs + idx * 50L,
                        isGroupSummary = false,
                        subMessages = emptyList(),
                        fromSummary = true,
                        notificationId = notif.notificationId + "/" + idx
                    )
                )
            }
            return
        }
        ingestSingle(notif)
    }

    /**
     * Concurrency-safe ingestion of a read-only accessibility screen capture.
     * - Serialized under finalizeLock (obeying replayLock -> finalizeLock -> bufferLock).
     * - BILL_DETAIL can ONLY merge into an existing buffer or backfill a uniquely matched committed tx
     *   with a reliable page transaction timestamp; it has NO code path to create a new transaction.
     * - Live PAY_SUCCESS / RECEIVE_SUCCESS checks active externalId idempotency across both buffers
     *   and committed active links before attempting buffer merge, backfill, or new enqueue.
     */
    fun ingestScreenCapture(capture: ScreenBillCapture): ScreenIngestOutcome {
        val raw = capture.toRawNotification()
        val clue = extractClue(raw)
        if (clue.amountMinor == null) return ScreenIngestOutcome.IGNORED

        val outcome = synchronized(replayLock) {
            synchronized(finalizeLock) {
                val extId = capture.externalId
                val rcptKey = capture.receiptDedupKey

                // 0. Check restore barrier before any read/write (restoring cannot toggle while finalizeLock is held)
                val deferredAtEntry = synchronized(bufferLock) {
                    if (restoring) {
                        deferredDuringRestore.add(DeferredEvent.Screen(capture))
                        true
                    } else {
                        false
                    }
                }
                if (deferredAtEntry) return@synchronized ScreenIngestOutcome.DEFERRED_RESTORE

                // 1. BILL_DETAIL pre-check BEFORE touching any buffer or DB:
                //    must carry a reliable page transaction timestamp; never match/merge using browse time!
                if (capture.pageKind == ScreenPageKind.BILL_DETAIL && capture.eventTimeMs == null) {
                    store.addLog(
                        "SCREEN_SKIP",
                        "账单详情页(${capture.appName} ${MoneyMath.formatUnsignedMinor(capture.amountMinor, capture.currency, capture.scale)})缺少可信交易时间，已跳过自动回填"
                    )
                    return@synchronized ScreenIngestOutcome.SKIPPED_NO_MATCH
                }

                // 2. Check active externalId & dual-second receiptDedupKey idempotency in DB BEFORE touching buffers
                if (!extId.isNullOrBlank() && store.hasActiveSourceExternalId(extId)) {
                    return@synchronized ScreenIngestOutcome.ALREADY_EXISTS
                }
                if (!rcptKey.isNullOrBlank() && store.hasActiveSourceFingerprint(rcptKey)) {
                    return@synchronized ScreenIngestOutcome.ALREADY_EXISTS
                }

                // 3. Atomic check for in-buffer idempotency and screen-safe buffer merge
                val bufferStep = synchronized(bufferLock) {
                    if (!extId.isNullOrBlank()) {
                        val inBuffer = buffers.values.any { g -> g.clues.any { it.raw.externalId == extId } }
                        if (inBuffer) return@synchronized ScreenIngestOutcome.ALREADY_EXISTS
                    }
                    if (!rcptKey.isNullOrBlank()) {
                        val inBuffer = buffers.values.any { g -> g.clues.any { it.sourceRecord.shortFingerprint == rcptKey } }
                        if (inBuffer) return@synchronized ScreenIngestOutcome.ALREADY_EXISTS
                    }
                    tryMergeScreenIntoExistingBufferLocked(capture, raw, clue)
                }
                if (bufferStep != null) return@synchronized bufferStep

                // 4. Attempt atomic in-place backfill against a uniquely matched committed transaction
                when (tryBackfillCommittedLocked(capture, raw, clue)) {
                    BackfillResult.ALREADY_EXISTS -> return@synchronized ScreenIngestOutcome.ALREADY_EXISTS
                    BackfillResult.BACKFILLED -> return@synchronized ScreenIngestOutcome.BACKFILLED_COMMITTED
                    BackfillResult.AMBIGUOUS -> {
                        if (capture.pageKind == ScreenPageKind.BILL_DETAIL) {
                            return@synchronized ScreenIngestOutcome.SKIPPED_AMBIGUOUS
                        }
                    }
                    BackfillResult.NO_MATCH -> {
                        if (capture.pageKind == ScreenPageKind.BILL_DETAIL) {
                            store.addLog(
                                "SCREEN_SKIP",
                                "账单详情页(${capture.appName} ${MoneyMath.formatUnsignedMinor(capture.amountMinor, capture.currency, capture.scale)})未匹配到可回填账单，未新增记录"
                            )
                            return@synchronized ScreenIngestOutcome.SKIPPED_NO_MATCH
                        }
                    }
                }

                // 4b. Guard against re-opening an old (>5 min) WeChat transfer receipt bubble without creating a duplicate tx
                if (capture.pageKind == ScreenPageKind.RECEIVE_SUCCESS &&
                    capture.eventTimeMs != null &&
                    (capture.observedAtMs - capture.eventTimeMs) > 300_000L
                ) {
                    store.addLog(
                        "SCREEN_SKIP",
                        "收款详情时间早于当前 5 分钟(${capture.appName} ${MoneyMath.formatUnsignedMinor(capture.amountMinor, capture.currency, capture.scale)})，判定为翻看历史气泡，未重复入账"
                    )
                    return@synchronized ScreenIngestOutcome.SKIPPED_NO_MATCH
                }

                // 5. Only live PAY_SUCCESS / recent RECEIVE_SUCCESS may enqueue a brand-new transaction
                val deferred = synchronized(bufferLock) {
                    if (restoring) {
                        deferredDuringRestore.add(DeferredEvent.Screen(capture))
                        true
                    } else {
                        enqueueIntoBufferLocked(raw, clue)
                        false
                    }
                }
                if (deferred) ScreenIngestOutcome.DEFERRED_RESTORE else ScreenIngestOutcome.ENQUEUED_NEW
            }
        }

        if (outcome == ScreenIngestOutcome.MERGED_INTO_BUFFER ||
            outcome == ScreenIngestOutcome.BACKFILLED_COMMITTED ||
            outcome == ScreenIngestOutcome.ENQUEUED_NEW
        ) {
            notifyChanged()
        }
        return outcome
    }

    /**
     * Merges a screen capture into an ALREADY EXISTING matching buffer under bufferLock.
     * - Enforces strong externalId conflict isolation, cross-platform isolation (WeChat != Alipay),
     *   strict acquirer/channel match for BILL_DETAIL, and unique-candidate requirement for BILL_DETAIL.
     * - Never creates a new buffer.
     */
    private fun tryMergeScreenIntoExistingBufferLocked(
        capture: ScreenBillCapture,
        raw: RawNotification,
        clue: ParsedClue
    ): ScreenIngestOutcome? {
        val key = raw.appPackage to raw.notificationId
        val event = raw.timestampMs to (raw.title to raw.content)
        if (delivered[key] == event) return ScreenIngestOutcome.ALREADY_EXISTS

        val amountMinor = clue.amountMinor ?: return null
        val refTime = if (capture.pageKind == ScreenPageKind.BILL_DETAIL) {
            capture.eventTimeMs ?: return ScreenIngestOutcome.SKIPPED_NO_MATCH
        } else {
            capture.eventTimeMs ?: capture.observedAtMs
        }
        val extId = capture.externalId
        val screenMerchant = notificationMerchant(raw)

        val candidates = buffers.values.filter { group ->
            if (abs(refTime - group.createdAtMs) > dedupWindowMs) return@filter false
            val gAmt = group.amountMinor ?: return@filter false
            if (gAmt != amountMinor || group.currency != clue.currency) return@filter false

            val sameDir = group.roughDirection == clue.roughDirection ||
                group.roughDirection == "UNKNOWN" || clue.roughDirection == "UNKNOWN" ||
                (group.roughDirection == "REFUND" && clue.roughDirection == "IN") ||
                (group.roughDirection == "IN" && clue.roughDirection == "REFUND")
            if (!sameDir) return@filter false

            // Never merge into a buffer carrying a DIFFERENT genuine platform order ID
            if (!extId.isNullOrBlank()) {
                val conflictingOrder = group.clues.any {
                    !it.raw.externalId.isNullOrBlank() && it.raw.externalId != extId
                }
                if (conflictingOrder) return@filter false
            }

            // Never merge WeChat capture into Alipay buffer or vice versa
            val otherPlatform = if (capture.appPackage == ScreenBillParser.PKG_WECHAT) {
                ScreenBillParser.PKG_ALIPAY
            } else {
                ScreenBillParser.PKG_WECHAT
            }
            if (group.clues.any { it.raw.appPackage == otherPlatform }) return@filter false

            // For BILL_DETAIL, require explicit same-platform or acquirer tag match on the buffer
            if (capture.pageKind == ScreenPageKind.BILL_DETAIL) {
                val channelMatches = group.clues.any { prior ->
                    prior.raw.appPackage == capture.appPackage ||
                        matchesPlatformAcquirer(capture.appPackage, "${prior.raw.title} ${prior.raw.content}")
                }
                if (!channelMatches) return@filter false
            }

            if (group.clues.any { it.cardTail != null && clue.cardTail != null && it.cardTail != clue.cardTail }) {
                return@filter false
            }

            val merchantConflict = group.clues.any { prior ->
                val other = notificationMerchant(prior.raw)
                !isGenericMerchantName(screenMerchant) &&
                    !isGenericMerchantName(other) &&
                    !screenMerchant.contains(other) &&
                    !other.contains(screenMerchant)
            }
            if (merchantConflict) return@filter false

            // Avoid merging a second accessibility capture from the same app into the same buffer
            val existingSources = group.clues.map { it.raw.appPackage to it.raw.sourceType }.toSet()
            if ((raw.appPackage to raw.sourceType) in existingSources) return@filter false

            true
        }

        if (candidates.isEmpty()) return null
        if (capture.pageKind == ScreenPageKind.BILL_DETAIL && candidates.size > 1) {
            store.addLog(
                "SCREEN_SKIP",
                "缓冲窗内存在 ${candidates.size} 笔同额候选，详情页为防错配跳过合并"
            )
            return ScreenIngestOutcome.SKIPPED_AMBIGUOUS
        }

        val matched = if (candidates.size == 1) {
            candidates.single()
        } else {
            candidates.minBy { abs(refTime - it.createdAtMs) }
        }
        mergeClueIntoBufferLocked(matched, raw, clue, key, event)
        return ScreenIngestOutcome.MERGED_INTO_BUFFER
    }

    private fun tryMergeIntoExistingBufferLocked(raw: RawNotification, clue: ParsedClue): Boolean {
        val key = raw.appPackage to raw.notificationId
        val event = raw.timestampMs to (raw.title to raw.content)
        if (delivered[key] == event) return false

        val matched = findMatchingBuffer(clue, raw.timestampMs) ?: return false
        mergeClueIntoBufferLocked(matched, raw, clue, key, event)
        return true
    }

    private fun mergeClueIntoBufferLocked(
        matched: BufferedGroup,
        raw: RawNotification,
        clue: ParsedClue,
        key: Pair<String, String>,
        event: Pair<Long, Pair<String, String>>
    ) {
        matched.clues.add(clue)
        if (clue.roughDirection == "REFUND") {
            matched.roughDirection = "REFUND"
        } else if (matched.roughDirection == "UNKNOWN" && clue.roughDirection != "UNKNOWN") {
            matched.roughDirection = clue.roughDirection
        }
        store.addLog(
            "DEDUP_MERGE",
            "防重合并命中！将【${raw.appName}/${raw.sourceType}】合入已有同笔缓冲（共 ${matched.clues.size} 条，${MoneyMath.formatUnsignedMinor(matched.amountMinor ?: 0L, matched.currency, matched.scale)}）"
        )
        store.enqueueOffline(OfflineQueueItem(matched.groupId, nowProvider(), buildMergedPayload(matched)))
        delivered[key] = event
        if (delivered.size > 256) delivered.remove(delivered.keys.first())
    }

    private fun matchesPlatformAcquirer(appPackage: String, text: String): Boolean {
        return when (appPackage) {
            ScreenBillParser.PKG_WECHAT -> text.contains("财付通") || text.contains("微信")
            ScreenBillParser.PKG_ALIPAY -> text.contains("支付宝")
            else -> false
        }
    }

    private fun isGenericMerchantName(merchant: String): Boolean {
        return merchant.isBlank() ||
            merchant == "未知商户" ||
            merchant in AiClient.GENERIC_GATEWAY_MERCHANTS ||
            merchant in setOf("微信收款", "支付宝收款")
    }

    private fun enqueueIntoBufferLocked(raw: RawNotification, clue: ParsedClue) {
        val key = raw.appPackage to raw.notificationId
        val event = raw.timestampMs to (raw.title to raw.content)
        if (delivered[key] == event) return
        if (tryMergeIntoExistingBufferLocked(raw, clue)) return

        val gid = UUID.randomUUID().toString()
        val baseExpiry = max(raw.timestampMs, nowProvider())
        val group = BufferedGroup(
            groupId = gid,
            createdAtMs = raw.timestampMs,
            expiresAtMs = baseExpiry + dedupWindowMs,
            amountMinor = clue.amountMinor,
            currency = clue.currency,
            scale = clue.scale,
            roughDirection = clue.roughDirection,
            clues = mutableListOf(clue)
        )
        buffers[gid] = group
        store.enqueueOffline(OfflineQueueItem(group.groupId, nowProvider(), buildMergedPayload(group)))
        delivered[key] = event
        if (delivered.size > 256) delivered.remove(delivered.keys.first())
    }

    private fun tryBackfillCommittedLocked(
        capture: ScreenBillCapture,
        raw: RawNotification,
        clue: ParsedClue
    ): BackfillResult {
        val extId = capture.externalId
        if (!extId.isNullOrBlank() && store.hasActiveSourceExternalId(extId)) {
            return BackfillResult.ALREADY_EXISTS
        }

        // BILL_DETAIL requires a reliable page transaction timestamp; never backfill by browse time!
        val refTime: Long
        val windowMs: Long
        if (capture.pageKind == ScreenPageKind.BILL_DETAIL) {
            val pageTime = capture.eventTimeMs
            if (pageTime == null) {
                store.addLog(
                    "SCREEN_SKIP",
                    "账单详情页(${capture.appName} ${MoneyMath.formatUnsignedMinor(capture.amountMinor, capture.currency, capture.scale)})缺少可信交易时间，已跳过自动回填"
                )
                return BackfillResult.NO_MATCH
            }
            refTime = pageTime
            windowMs = 300_000L
        } else {
            refTime = capture.eventTimeMs ?: capture.observedAtMs
            windowMs = 90_000L
        }

        val sanitizedMerchant = PrivacySanitizer.sanitize(capture.merchant, profile).first.trim()
        val hasConcreteScreenMerchant = sanitizedMerchant.length >= 2 &&
            sanitizedMerchant != "未知商户" &&
            sanitizedMerchant !in AiClient.GENERIC_GATEWAY_MERCHANTS

        val rawCandidates = store.findBackfillCandidates(
            amountMinor = capture.amountMinor,
            currency = capture.currency,
            sinceMs = refTime - windowMs,
            untilMs = refTime + windowMs
        )
        val candidates = rawCandidates.filter { tx ->
            val dirMatches = when (capture.directionHint) {
                "OUT" -> tx.direction == TxDirection.EXPENSE
                "IN" -> tx.direction == TxDirection.INCOME || tx.direction == TxDirection.REIMBURSEMENT_OFFSET
                "REFUND" -> tx.direction == TxDirection.REFUND
                else -> false
            }
            if (!dirMatches) return@filter false

            val existingSources = store.listSourcesForTransaction(tx.id)
            // Strict channel match: must belong to same platform or explicitly match its acquirer tag
            val acquirerMatches = when (capture.appPackage) {
                ScreenBillParser.PKG_WECHAT -> tx.sanitizedText.contains("财付通") || tx.sanitizedText.contains("微信")
                ScreenBillParser.PKG_ALIPAY -> tx.sanitizedText.contains("支付宝")
                else -> false
            }
            val channelMatches = capture.appName in tx.sourceChannels ||
                tx.paymentMethod.contains(capture.appName) ||
                existingSources.any { it.first.appPackage == capture.appPackage } ||
                (tx.merchant == "未知商户" && acquirerMatches)
            if (!channelMatches) return@filter false

            // Never merge into a transaction that already holds a DIFFERENT genuine platform order ID
            if (!extId.isNullOrBlank()) {
                val conflictingOrder = existingSources.any { (src, _) ->
                    !src.externalId.isNullOrBlank() && src.externalId != extId
                }
                if (conflictingOrder) return@filter false
            }

            val existingMerchantGeneric = tx.merchant == "未知商户" ||
                tx.merchant in AiClient.GENERIC_GATEWAY_MERCHANTS ||
                tx.merchant in setOf("微信收款", "支付宝收款")
            val merchantCompatible = existingMerchantGeneric ||
                !hasConcreteScreenMerchant ||
                tx.merchant.contains(sanitizedMerchant) ||
                sanitizedMerchant.contains(tx.merchant)
            if (!merchantCompatible) return@filter false

            if (capture.pageKind != ScreenPageKind.BILL_DETAIL &&
                existingSources.any { it.first.sourceType == ScreenBillParser.SOURCE_TYPE_ACCESSIBILITY }
            ) {
                return@filter false
            }
            if (capture.pageKind == ScreenPageKind.BILL_DETAIL &&
                !existingMerchantGeneric &&
                existingSources.any { it.first.sourceType == ScreenBillParser.SOURCE_TYPE_ACCESSIBILITY }
            ) {
                return@filter false
            }
            true
        }

        if (candidates.size > 1) {
            store.addLog(
                "SCREEN_SKIP",
                "时间窗内存在 ${candidates.size} 笔同额候选，为防错配跳过自动回填"
            )
            return BackfillResult.AMBIGUOUS
        }
        if (candidates.isEmpty()) return BackfillResult.NO_MATCH

        val target = candidates.single()
        val updated = store.runInTransaction {
            val existing = store.getTransaction(target.id) ?: return@runInTransaction null
            val existingPaired = store.listSourcesForTransaction(existing.id)
            val existingLinks = existingPaired.map { it.second }
            val now = nowProvider()

            val existingMerchantGeneric = existing.merchant == "未知商户" ||
                existing.merchant in AiClient.GENERIC_GATEWAY_MERCHANTS ||
                existing.merchant in setOf("微信收款", "支付宝收款")
            val shouldUpgradeMerchant = hasConcreteScreenMerchant && existingMerchantGeneric
            val finalMerchant = if (shouldUpgradeMerchant) sanitizedMerchant else existing.merchant

            val sanitizedLocalItem = capture.itemDescription
                .takeIf { it.length >= 2 }
                ?.let { PrivacySanitizer.sanitize(it, profile).first.trim() }
                ?.takeIf { it.length >= 2 }

            val genericCategories = setOf("日用", "副业/其他收入", "人情转账")
            val finalCategory = if (shouldUpgradeMerchant && existing.category in genericCategories) {
                AiClient.inferCategory("${capture.structuredNoteTag} ${sanitizedLocalItem.orEmpty()} $finalMerchant", finalMerchant, existing.direction)
            } else {
                existing.category
            }

            val finalSummary = if (shouldUpgradeMerchant && existing.itemSummary.startsWith("未知商户")) {
                sanitizedLocalItem ?: "$finalMerchant 交易"
            } else {
                existing.itemSummary
            }

            val updatedChannels = (existing.sourceChannels + capture.appName).distinct()
            val noteSuffix = if (shouldUpgradeMerchant) {
                " [屏幕回填商户:$finalMerchant]"
            } else {
                " [已关联${capture.appName}屏幕证据]"
            }

            val newTx = existing.copy(
                merchant = finalMerchant,
                category = finalCategory,
                itemSummary = finalSummary,
                sourceChannels = updatedChannels,
                mergedNotifications = existing.mergedNotifications + 1,
                updatedAtMs = now,
                revision = existing.revision + 1,
                aiReasoning = existing.aiReasoning + noteSuffix
            )

            val screenSrc = clue.sourceRecord.copy(
                merchantHint = if (hasConcreteScreenMerchant) sanitizedMerchant else clue.sourceRecord.merchantHint
            )
            val link = TransactionSourceLink(
                transactionId = newTx.id,
                sourceId = screenSrc.id,
                linkRole = SourceLinkRole.CORROBORATING_CHANNEL,
                candidateId = clue.primaryCandidateId
            )

            val change = TransactionChange(
                opType = ChangeOpType.MERGE,
                affectedTxIds = listOf(existing.id),
                beforeRevisions = mapOf(existing.id to existing.revision),
                afterRevisions = mapOf(existing.id to newTx.revision),
                inversePayloadJson = JSONObject().apply {
                    put("transactions", JSONArray().put(BackupCodec.txToJson(existing)))
                    put("restoreLinksTxIds", JSONArray().put(existing.id))
                    put("restoreLinks", JSONArray().apply {
                        for (l in existingLinks) {
                            put(JSONObject().apply {
                                put("transactionId", l.transactionId)
                                put("sourceId", l.sourceId)
                                put("linkRole", l.linkRole.name)
                                put("candidateId", l.candidateId ?: JSONObject.NULL)
                            })
                        }
                    })
                }.toString(),
                createdAtMs = now
            )

            store.saveTransactionBundle(
                transactions = listOf(newTx),
                sources = listOf(screenSrc),
                links = listOf(link),
                change = change
            )
            newTx
        }

        return if (updated != null) {
            store.addLog(
                "SCREEN_BACKFILL",
                "屏幕增强已合并到既有账单(${updated.id.take(8)})：${updated.merchant} ${MoneyMath.formatUnsignedMinor(updated.amountMinor, updated.currency, updated.scale)} (${updated.category})"
            )
            BackfillResult.BACKFILLED
        } else {
            BackfillResult.NO_MATCH
        }
    }

    private fun ingestSingle(notif: RawNotification) {
        val combined = "${notif.title} ${notif.content}".trim()
        if (!isFinanceRelated(combined)) {
            return
        }

        val clue = extractClue(notif)
        if (clue.amountMinor == null && !clue.isZeroAuth) {
            return
        }
        val (previewSanitized, _) = PrivacySanitizer.sanitize(
            "[${notif.appName}] ${notif.title}: ${notif.content}",
            profile
        )
        store.addLog(
            "CAPTURE",
            "捕获动账通知：${notif.appName} | 预提金额=${clue.amountMinor?.let { MoneyMath.formatUnsignedMinor(it, clue.currency, clue.scale) } ?: "未知"} | 粗判=${clue.roughDirection}",
            previewSanitized
        )

        if (clue.isZeroAuth) {
            synchronized(finalizeLock) {
                synchronized(bufferLock) {
                    if (restoring) {
                        deferredDuringRestore.add(DeferredEvent.Notif(notif))
                        return
                    }
                }
                val group = BufferedGroup(
                    groupId = UUID.randomUUID().toString(),
                    createdAtMs = notif.timestampMs,
                    expiresAtMs = notif.timestampMs,
                    amountMinor = clue.amountMinor,
                    currency = clue.currency,
                    scale = clue.scale,
                    roughDirection = "ZERO_AUTH",
                    clues = mutableListOf(clue)
                )
                processGroup(group)
            }
            notifyChanged()
            return
        }

        synchronized(bufferLock) {
            if (restoring) {
                deferredDuringRestore.add(DeferredEvent.Notif(notif))
                return
            }
            enqueueIntoBufferLocked(notif, clue)
        }
        notifyChanged()
    }

    fun activeBufferCount(): Int = synchronized(bufferLock) { buffers.size }

    fun flushExpired(forceAll: Boolean = false, nowMs: Long = nowProvider()): List<Transaction> = synchronized(replayLock) {
        if (restoring) return emptyList()
        val ready = mutableListOf<BufferedGroup>()
        synchronized(bufferLock) {
            val it = buffers.entries.iterator()
            while (it.hasNext()) {
                val g = it.next().value
                if (forceAll || nowMs >= g.expiresAtMs) {
                    ready.add(g)
                    it.remove()
                }
            }
        }
        if (ready.isEmpty()) return emptyList()

        val created = mutableListOf<Transaction>()
        for (group in ready) {
            val result = processGroup(group)
            if (result != null) {
                created.addAll(result)
                store.removeOfflineItem(group.groupId)
            }
        }
        notifyChanged()
        created
    }

    fun clearAllData() = synchronized(replayLock) {
        synchronized(bufferLock) {
            buffers.clear()
            delivered.clear()
            store.clearLedgerAndLogs()
        }
        notifyChanged()
    }

    fun replayOfflineQueue(): Int = synchronized(replayLock) {
        if (restoring) return 0
        if (aiConfig.isRealApiEnabled && !isNetworkAvailable()) return 0
        val queued = store.listOfflineQueue()
        var replayed = 0
        try {
            for (item in queued) {
                if (synchronized(bufferLock) { item.queueId in buffers }) continue
                try {
                    if (!isGroupAlreadyCommitted(item.queueId, item.payload)) {
                        finalizePayload(item.payload)
                    }
                    store.removeOfflineItem(item.queueId)
                    replayed++
                } catch (e: java.io.IOException) {
                    if (item.failures < 2) {
                        store.enqueueOffline(item.copy(failures = item.failures + 1))
                        break
                    }
                    val local = AiClient.parseMock(profile, item.payload).copy(
                        needsReview = true,
                        aiModeUsed = "retry_limit",
                        reasoning = "网络补发连续失败，已转本地待核对"
                    )
                    synchronized(finalizeLock) { commitParsedTransactions(item.payload, local) }
                    store.removeOfflineItem(item.queueId)
                    replayed++
                }
            }
        } finally {
            if (replayed > 0) {
                store.addLog("OFFLINE_REPLAY", "已自动补发并入账 $replayed 笔待处理队列记录")
                notifyChanged()
            }
        }
        replayed
    }

    private fun isGroupAlreadyCommitted(queueId: String, payload: MergedPayload): Boolean {
        if (store.getTransaction(queueId) != null) return true
        // Also check if any source in this group was already linked into an existing transaction (e.g. split funding)
        for (s in payload.sources) {
            if (store.listLinksForSource(s.id).isNotEmpty()) return true
        }
        return false
    }

    fun rescueIgnored(
        txId: String,
        newDirection: TxDirection = TxDirection.EXPENSE,
        expectedRevision: Int? = null
    ): Transaction? = synchronized(finalizeLock) {
        val result = store.runInTransaction {
            val old = store.getTransaction(txId) ?: return@runInTransaction null
            if (expectedRevision != null && old.revision != expectedRevision) return@runInTransaction null
            val now = nowProvider()
            val rescued = old.copy(
                direction = newDirection,
                status = TxStatus.RECORDED,
                category = when (newDirection) {
                    TxDirection.EXPENSE -> "日用"
                    TxDirection.INCOME -> "副业/其他收入"
                    TxDirection.REIMBURSEMENT_OFFSET -> "AA/报销冲抵"
                    TxDirection.REFUND -> "退款冲抵"
                    else -> old.category
                },
                ignoreReason = "",
                rescuedByUser = true,
                updatedAtMs = now,
                revision = old.revision + 1,
                aiReasoning = "[用户手动捞回主账本] ${old.aiReasoning}"
            )
            val change = TransactionChange(
                opType = ChangeOpType.RESCUE,
                affectedTxIds = listOf(old.id),
                beforeRevisions = mapOf(old.id to old.revision),
                afterRevisions = mapOf(old.id to rescued.revision),
                inversePayloadJson = JSONObject().apply {
                    put("transactions", JSONArray().put(BackupCodec.txToJson(old)))
                }.toString(),
                createdAtMs = now
            )
            store.saveTransaction(rescued)
            store.saveChange(change)
            rescued
        }
        if (result != null) {
            store.addLog("USER_RESCUE", "手动将【${result.merchant} ${MoneyMath.formatUnsignedMinor(result.amountMinor, result.currency, result.scale)}】捞回主账本")
            notifyChanged()
        }
        result
    }

    fun markAsInternal(
        txId: String,
        reason: String = "用户手动标为内部互转",
        expectedRevision: Int? = null
    ): TransactionChange? = synchronized(finalizeLock) {
        val change = store.runInTransaction {
            val old = store.getTransaction(txId) ?: return@runInTransaction null
            if (expectedRevision != null && old.revision != expectedRevision) return@runInTransaction null
            val now = nowProvider()
            val updated = old.copy(
                direction = TxDirection.INTERNAL_TRANSFER,
                status = TxStatus.IGNORED,
                category = "内部流转",
                ignoreReason = reason,
                updatedAtMs = now,
                revision = old.revision + 1
            )
            val ch = TransactionChange(
                opType = ChangeOpType.MARK_INTERNAL,
                affectedTxIds = listOf(old.id),
                beforeRevisions = mapOf(old.id to old.revision),
                afterRevisions = mapOf(old.id to updated.revision),
                inversePayloadJson = JSONObject().apply {
                    put("transactions", JSONArray().put(BackupCodec.txToJson(old)))
                }.toString(),
                createdAtMs = now
            )
            store.saveTransaction(updated)
            store.saveChange(ch)
            ch
        }
        if (change != null) notifyChanged()
        change
    }

    fun markAsReimbursementOffset(
        txId: String,
        relatedExpenseId: String? = null,
        expectedRevision: Int? = null
    ): TransactionChange? = synchronized(finalizeLock) {
        val change = store.runInTransaction {
            val old = store.getTransaction(txId) ?: return@runInTransaction null
            if (expectedRevision != null && old.revision != expectedRevision) return@runInTransaction null
            val now = nowProvider()
            val updated = old.copy(
                direction = TxDirection.REIMBURSEMENT_OFFSET,
                status = TxStatus.RECORDED,
                category = "AA/报销冲抵",
                relatedTxId = relatedExpenseId ?: old.relatedTxId,
                updatedAtMs = now,
                revision = old.revision + 1
            )
            val ch = TransactionChange(
                opType = ChangeOpType.MARK_OFFSET,
                affectedTxIds = listOf(old.id),
                beforeRevisions = mapOf(old.id to old.revision),
                afterRevisions = mapOf(old.id to updated.revision),
                inversePayloadJson = JSONObject().apply {
                    put("transactions", JSONArray().put(BackupCodec.txToJson(old)))
                }.toString(),
                createdAtMs = now
            )
            store.saveTransaction(updated)
            store.saveChange(ch)
            ch
        }
        if (change != null) notifyChanged()
        change
    }

    /**
     * Splits a wrongly merged transaction back into independent transactions per linked source.
     * Refuses to fabricate splits for legacy rows marked legacyMissingSources = true.
     */
    fun splitMergedTransaction(txId: String, expectedRevision: Int? = null): Pair<List<Transaction>, TransactionChange>? =
        synchronized(finalizeLock) {
            val out = store.runInTransaction {
                val old = store.getTransaction(txId) ?: return@runInTransaction null
                if (expectedRevision != null && old.revision != expectedRevision) return@runInTransaction null
                if (old.legacyMissingSources) return@runInTransaction null
                val paired = store.listSourcesForTransaction(txId)
                if (paired.size < 2) return@runInTransaction null

                val now = nowProvider()
                val oldLinks = paired.map { it.second }
                val primaryPair = paired.first()
                val primaryCand = primaryPair.second.candidateId?.let { cid ->
                    primaryPair.first.amountCandidates.firstOrNull { it.candidateId == cid }
                } ?: primaryPair.first.amountCandidates.firstOrNull { AmountCandidateExtractor.isRoleBookableAsPrimary(it.role) }

                val updatedPrimary = old.copy(
                    amountMinor = primaryCand?.amountMinor ?: old.amountMinor,
                    currency = primaryCand?.currency ?: old.currency,
                    scale = primaryCand?.scale ?: old.scale,
                    sourceChannels = listOf(primaryPair.first.appName),
                    mergedNotifications = 1,
                    updatedAtMs = now,
                    revision = old.revision + 1,
                    aiReasoning = "[用户已拆分误合并来源] ${old.aiReasoning}"
                )

                val spawnedTxs = mutableListOf<Transaction>()
                val newLinks = mutableListOf(
                    TransactionSourceLink(
                        transactionId = updatedPrimary.id,
                        sourceId = primaryPair.first.id,
                        linkRole = SourceLinkRole.PRIMARY_PAYMENT,
                        candidateId = primaryCand?.candidateId ?: primaryPair.second.candidateId
                    )
                )

                for ((src, link) in paired.drop(1)) {
                    val cand = link.candidateId?.let { cid ->
                        src.amountCandidates.firstOrNull { it.candidateId == cid }
                    } ?: src.amountCandidates.firstOrNull { AmountCandidateExtractor.isRoleBookableAsPrimary(it.role) }
                    val newId = UUID.randomUUID().toString()
                    val splitTx = Transaction(
                        id = newId,
                        occurredAtMs = src.sourceEventTimeMs ?: src.observedAtMs,
                        observedAtMs = src.observedAtMs,
                        updatedAtMs = now,
                        revision = 1,
                        direction = old.direction,
                        amountMinor = cand?.amountMinor ?: old.amountMinor,
                        currency = cand?.currency ?: old.currency,
                        scale = cand?.scale ?: old.scale,
                        merchant = src.merchantHint.ifBlank { old.merchant },
                        itemSummary = "${src.merchantHint.ifBlank { old.merchant }}（拆分自合并账单）",
                        category = old.category,
                        paymentMethod = src.channelLabel.ifBlank { src.appName },
                        sourceChannels = listOf(src.appName),
                        mergedNotifications = 1,
                        status = TxStatus.NEEDS_REVIEW,
                        aiModeUsed = "user_split",
                        aiReasoning = "从原合并账单(${old.id.take(8)})拆出的独立来源"
                    )
                    spawnedTxs.add(splitTx)
                    newLinks.add(
                        TransactionSourceLink(
                            transactionId = newId,
                            sourceId = src.id,
                            linkRole = SourceLinkRole.PRIMARY_PAYMENT,
                            candidateId = cand?.candidateId ?: link.candidateId
                        )
                    )
                }

                val affectedIds = listOf(old.id) + spawnedTxs.map { it.id }
                val beforeRevs = mapOf(old.id to old.revision)
                val afterRevs = buildMap {
                    put(updatedPrimary.id, updatedPrimary.revision)
                    for (t in spawnedTxs) put(t.id, t.revision)
                }
                val inverse = JSONObject().apply {
                    put("transactions", JSONArray().put(BackupCodec.txToJson(old)))
                    put("removeTxIds", JSONArray(spawnedTxs.map { it.id }))
                    put("restoreLinks", JSONArray().apply {
                        for (l in oldLinks) {
                            put(JSONObject().apply {
                                put("transactionId", l.transactionId)
                                put("sourceId", l.sourceId)
                                put("linkRole", l.linkRole.name)
                                put("candidateId", l.candidateId ?: JSONObject.NULL)
                            })
                        }
                    })
                }
                val ch = TransactionChange(
                    opType = ChangeOpType.SPLIT,
                    affectedTxIds = affectedIds,
                    beforeRevisions = beforeRevs,
                    afterRevisions = afterRevs,
                    inversePayloadJson = inverse.toString(),
                    createdAtMs = now
                )

                store.deleteLinksForTransaction(old.id)
                store.saveTransaction(updatedPrimary)
                for (t in spawnedTxs) store.saveTransaction(t)
                store.saveLinks(newLinks)
                store.saveChange(ch)

                (listOf(updatedPrimary) + spawnedTxs) to ch
            }
            if (out != null) notifyChanged()
            out
        }

    /**
     * Transactionally undoes a prior operation ONLY if every affected transaction is still at the exact
     * post-operation revision (prevents stale undo buttons from clobbering newer user edits).
     */
    fun undoChange(opId: String): Boolean = synchronized(finalizeLock) {
        val ok = store.runInTransaction {
            val change = store.getChange(opId) ?: return@runInTransaction false
            if (change.opType == ChangeOpType.UNDO) return@runInTransaction false

            // Revision check across all affected transactions
            for ((txId, expectedRev) in change.afterRevisions) {
                val cur = store.getTransaction(txId) ?: return@runInTransaction false
                if (cur.revision != expectedRev) return@runInTransaction false
            }

            val inv = JSONObject(change.inversePayloadJson)
            val now = nowProvider()
            val afterUndoRevs = LinkedHashMap<String, Int>()

            // Mark any spawned transactions as REVERTED instead of hard deleting to preserve audit chain
            val removeArr = inv.optJSONArray("removeTxIds")
            if (removeArr != null) {
                for (i in 0 until removeArr.length()) {
                    val rid = removeArr.getString(i)
                    val spawned = store.getTransaction(rid) ?: continue
                    store.deleteLinksForTransaction(rid)
                    val reverted = spawned.copy(
                        status = TxStatus.REVERTED,
                        updatedAtMs = now,
                        revision = spawned.revision + 1
                    )
                    store.saveTransaction(reverted)
                    afterUndoRevs[rid] = reverted.revision
                }
            }

            // Restore prior transaction states with incremented revision
            val txArr = inv.optJSONArray("transactions")
            if (txArr != null) {
                for (i in 0 until txArr.length()) {
                    val prev = BackupCodec.txFromJson(txArr.getJSONObject(i))
                    val cur = store.getTransaction(prev.id)
                    val nextRev = (cur?.revision ?: prev.revision) + 1
                    val restored = prev.copy(updatedAtMs = now, revision = nextRev)
                    store.saveTransaction(restored)
                    afterUndoRevs[restored.id] = nextRev
                }
            }

            // Restore links if present (explicitly handles empty link sets too!)
            val linkArr = inv.optJSONArray("restoreLinks")
            if (linkArr != null) {
                val groupedByTx = mutableMapOf<String, MutableList<TransactionSourceLink>>()
                val txIdsToReset = LinkedHashSet<String>()
                val clearIdsArr = inv.optJSONArray("restoreLinksTxIds")
                if (clearIdsArr != null) {
                    for (i in 0 until clearIdsArr.length()) {
                        txIdsToReset.add(clearIdsArr.getString(i))
                    }
                } else {
                    txIdsToReset.addAll(change.affectedTxIds)
                }
                for (i in 0 until linkArr.length()) {
                    val lo = linkArr.getJSONObject(i)
                    val link = TransactionSourceLink(
                        transactionId = lo.getString("transactionId"),
                        sourceId = lo.getString("sourceId"),
                        linkRole = SourceLinkRole.valueOf(lo.getString("linkRole")),
                        candidateId = if (lo.isNull("candidateId")) null else lo.optString("candidateId").ifBlank { null }
                    )
                    txIdsToReset.add(link.transactionId)
                    groupedByTx.getOrPut(link.transactionId) { mutableListOf() }.add(link)
                }
                for (tid in txIdsToReset) {
                    store.deleteLinksForTransaction(tid)
                    val toSave = groupedByTx[tid].orEmpty()
                    if (toSave.isNotEmpty()) store.saveLinks(toSave)
                }
            }

            store.saveChange(
                TransactionChange(
                    opType = ChangeOpType.UNDO,
                    affectedTxIds = change.affectedTxIds,
                    beforeRevisions = change.afterRevisions,
                    afterRevisions = afterUndoRevs,
                    inversePayloadJson = JSONObject().put("undoneOpId", opId).toString(),
                    createdAtMs = now
                )
            )
            true
        }
        if (ok) notifyChanged()
        ok
    }

    fun getSummary(nowMs: Long = nowProvider()): LedgerSummary {
        val all = store.listTransactions(limit = Int.MAX_VALUE).filter { it.status != TxStatus.REVERTED }
        val active = all.filter { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW }
        val ignored = all.filter { it.status == TxStatus.IGNORED }
        val needsReviewTotal = all.count { it.status == TxStatus.NEEDS_REVIEW }

        val zone = java.time.ZoneId.systemDefault()
        val month = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(zone))
        val monthlyAll = all.filter {
            java.time.YearMonth.from(java.time.Instant.ofEpochMilli(it.occurredAtMs).atZone(zone)) == month
        }

        val byCurrency = LinkedHashMap<String, CurrencyTotals>()
        val currencies = (all.map { it.currency to it.scale } + ("CNY" to 2)).distinctBy { it.first }
        for ((curr, scale) in currencies) {
            val mCurr = monthlyAll.filter { it.currency == curr }
            val mActive = mCurr.filter { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW }
            val grossMinor = mActive.filter { it.direction == TxDirection.EXPENSE }.sumOf { it.amountMinor }
            val refundMinor = mActive.filter { it.direction == TxDirection.REFUND }.sumOf { it.amountMinor }
            val offsetMinor = mActive.filter { it.direction == TxDirection.REIMBURSEMENT_OFFSET }.sumOf { it.amountMinor }
            val netPersonalMinor = grossMinor - refundMinor - offsetMinor
            val incomeMinor = mActive.filter { it.direction == TxDirection.INCOME }.sumOf { it.amountMinor }
            val cashInflowMinor = incomeMinor + refundMinor + offsetMinor
            val netBalMinor = incomeMinor - netPersonalMinor
            val allCurr = all.filter { it.currency == curr }
            val currIgnored = allCurr.filter { it.status == TxStatus.IGNORED }
            byCurrency[curr] = CurrencyTotals(
                currency = curr,
                scale = scale,
                grossExpenseMinor = grossMinor,
                totalRefundMinor = refundMinor,
                totalOffsetMinor = offsetMinor,
                netPersonalExpenseMinor = netPersonalMinor,
                totalIncomeMinor = incomeMinor,
                actualCashInflowMinor = cashInflowMinor,
                netBalanceMinor = netBalMinor,
                recordedCount = allCurr.count { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW },
                needsReviewCount = allCurr.count { it.status == TxStatus.NEEDS_REVIEW },
                ignoredCount = currIgnored.size,
                internalTransferCount = currIgnored.count { it.direction == TxDirection.INTERNAL_TRANSFER },
                otherIgnoredCount = currIgnored.count { it.direction != TxDirection.INTERNAL_TRANSFER }
            )
        }

        val cny = byCurrency["CNY"]!!
        val internalTotal = ignored.count { it.direction == TxDirection.INTERNAL_TRANSFER }
        val otherIgnoredTotal = ignored.count { it.direction != TxDirection.INTERNAL_TRANSFER }
        return LedgerSummary(
            grossExpense = MoneyMath.minorToDouble(cny.grossExpenseMinor, 2),
            totalRefund = MoneyMath.minorToDouble(cny.totalRefundMinor, 2),
            totalOffset = MoneyMath.minorToDouble(cny.totalOffsetMinor, 2),
            netExpense = MoneyMath.minorToDouble(cny.netPersonalExpenseMinor, 2),
            totalIncome = MoneyMath.minorToDouble(cny.totalIncomeMinor, 2),
            netBalance = MoneyMath.minorToDouble(cny.netBalanceMinor, 2),
            recordedCount = active.size,
            needsReviewCount = needsReviewTotal,
            ignoredCount = ignored.size,
            internalTransferCount = internalTotal,
            otherIgnoredCount = otherIgnoredTotal,
            offlineQueueCount = synchronized(bufferLock) { store.listOfflineQueue().count { it.queueId !in buffers } },
            dbSizeBytes = store.getDbSizeBytes(),
            byCurrency = byCurrency
        )
    }

    fun exportSanitizedDebugSamples(limit: Int = 40): String {
        return store.listTransactions(limit).joinToString("\n", prefix = "脱敏交易诊断（仅类型、状态、币种及合并数）\n") {
            "${it.direction.name} ${it.status.name} ${it.currency} 合并通知=${it.mergedNotifications}"
        }
    }

    private fun processGroup(group: BufferedGroup): List<Transaction>? {
        val payload = buildMergedPayload(group)
        if (payload.redactionNotes.isNotEmpty()) {
            store.addLog("SANITIZE", "隐私脱敏：${payload.redactionNotes.joinToString("、")}", payload.sanitizedText)
        }
        if (aiConfig.isRealApiEnabled && !isNetworkAvailable()) {
            store.enqueueOffline(OfflineQueueItem(group.groupId, nowProvider(), payload))
            store.addLog(
                "OFFLINE_QUEUE",
                "当前无网络，已将【${payload.sourceChannels.joinToString("+")} ${MoneyMath.formatUnsignedMinor(payload.amountMinor ?: 0L, payload.currency, payload.scale)}】暂存本地队列"
            )
            return null
        }
        return try {
            finalizePayload(payload)
        } catch (e: java.io.IOException) {
            store.enqueueOffline(OfflineQueueItem(group.groupId, nowProvider(), payload))
            store.addLog(
                "OFFLINE_QUEUE",
                "API 请求网络异常（${e.javaClass.simpleName}），已将 ${MoneyMath.formatUnsignedMinor(payload.amountMinor ?: 0L, payload.currency, payload.scale)} 暂存断网队列等待重发"
            )
            null
        }
    }

    private fun finalizePayload(payload: MergedPayload): List<Transaction> {
        val ai = parse(aiConfig, profile, payload)
        return synchronized(finalizeLock) {
            commitParsedTransactions(payload, ai)
        }
    }

    private fun commitParsedTransactions(payload: MergedPayload, ai: AiParseResult): List<Transaction> {
        val now = nowProvider()
        val sources = payload.sources.ifEmpty {
            listOf(
                TransactionSource(
                    id = "${payload.groupId}:src",
                    sourceType = "notification",
                    appPackage = "unknown",
                    appName = payload.sourceChannels.firstOrNull() ?: "未知来源",
                    observedAtMs = payload.observedAtMs,
                    sourceEventTimeMs = payload.createdAtMs,
                    amountCandidates = payload.candidates,
                    merchantHint = ai.merchant,
                    channelLabel = payload.paymentMethod
                )
            )
        }

        // Split-funding late bank alert link: if this is an EXPENSE matching an unlinked SPLIT_PART on an existing composite transaction, attach source instead of creating a duplicate!
        if (ai.direction == TxDirection.EXPENSE && ai.amountMinor > 0L && payload.splitParts.isEmpty() && sources.size == 1) {
            val matchedComposite = store.findRecentSplitFundedCandidate(
                partAmountMinor = ai.amountMinor,
                currency = ai.currency,
                sinceMs = payload.createdAtMs - 120_000L,
                untilMs = payload.createdAtMs + 120_000L
            )
            if (matchedComposite != null) {
                val (existingTx, compositeSrc) = matchedComposite
                val bankSrc = sources.single()
                val partCand = compositeSrc.amountCandidates.firstOrNull {
                    it.role == AmountRole.SPLIT_PART && it.currency == ai.currency && it.amountMinor == ai.amountMinor
                }
                val updatedChannels = (existingTx.sourceChannels + bankSrc.appName).distinct()
                val updatedTx = existingTx.copy(
                    sourceChannels = updatedChannels,
                    mergedNotifications = existingTx.mergedNotifications + 1,
                    updatedAtMs = now,
                    revision = existingTx.revision + 1,
                    aiReasoning = "${existingTx.aiReasoning} [已关联组合支付银行分摊来源:${bankSrc.appName}]"
                )
                val newLink = TransactionSourceLink(
                    transactionId = updatedTx.id,
                    sourceId = bankSrc.id,
                    linkRole = SourceLinkRole.SPLIT_FUNDING,
                    candidateId = partCand?.candidateId
                )
                val existingLinks = store.listSourcesForTransaction(existingTx.id).map { it.second }
                val change = TransactionChange(
                    opType = ChangeOpType.MERGE,
                    affectedTxIds = listOf(existingTx.id),
                    beforeRevisions = mapOf(existingTx.id to existingTx.revision),
                    afterRevisions = mapOf(existingTx.id to updatedTx.revision),
                    inversePayloadJson = JSONObject().apply {
                        put("transactions", JSONArray().put(BackupCodec.txToJson(existingTx)))
                        put("restoreLinks", JSONArray().apply {
                            for (l in existingLinks) {
                                put(JSONObject().apply {
                                    put("transactionId", l.transactionId)
                                    put("sourceId", l.sourceId)
                                    put("linkRole", l.linkRole.name)
                                    put("candidateId", l.candidateId ?: JSONObject.NULL)
                                })
                            }
                        })
                    }.toString(),
                    createdAtMs = now
                )
                store.saveTransactionBundle(
                    transactions = listOf(updatedTx),
                    sources = listOf(bankSrc),
                    links = listOf(newLink),
                    change = change
                )
                store.addLog("SPLIT_LINK", "银行分摊扣款 ${MoneyMath.formatUnsignedMinor(ai.amountMinor, ai.currency, ai.scale)} 已关联到组合支付主单(${updatedTx.id.take(8)})，未重复记账")
                return listOf(updatedTx)
            }
        }

        var direction = ai.direction
        var category = ai.category
        var status = when {
            payload.ambiguityReason != null -> TxStatus.NEEDS_REVIEW
            direction == TxDirection.INTERNAL_TRANSFER || direction == TxDirection.IGNORED -> TxStatus.IGNORED
            ai.needsReview -> TxStatus.NEEDS_REVIEW
            else -> TxStatus.RECORDED
        }
        var ignoreReason = if (status == TxStatus.IGNORED) ai.reasoning else ""
        var reasoning = ai.reasoning
        var relatedTxId: String? = null
        var mirrorChangeToSave: TransactionChange? = null
        var mirrorUpdatedTx: Transaction? = null

        // Strict mirror transfer netting: requires explicit transfer semantics, distinct own card tails, AND UNIQUE candidate!
        if (status == TxStatus.RECORDED &&
            (direction == TxDirection.EXPENSE || direction == TxDirection.INCOME) &&
            ai.amountMinor > 0L
        ) {
            val opposite = if (direction == TxDirection.EXPENSE) TxDirection.INCOME else TxDirection.EXPENSE
            val rawCandidates = store.findRecentMirrorCandidates(
                amountMinor = ai.amountMinor,
                currency = ai.currency,
                oppositeDirection = opposite,
                sinceMs = payload.createdAtMs - mirrorWindowMs,
                untilMs = payload.createdAtMs + mirrorWindowMs
            )
            val currentTail = extractOwnCardTail(ai.paymentMethod, payload.sanitizedText)
            val currentHasTransferWord = TRANSFER_SEMANTIC_WORDS.any { payload.sanitizedText.contains(it) }
            val qualified = if (currentTail != null && currentHasTransferWord) {
                rawCandidates.filter { cand ->
                    val candTail = extractOwnCardTail(cand.paymentMethod, cand.sanitizedText)
                    !cand.rescuedByUser &&
                        abs(payload.createdAtMs - cand.occurredAtMs) <= mirrorWindowMs &&
                        TRANSFER_SEMANTIC_WORDS.any { cand.sanitizedText.contains(it) } &&
                        candTail != null && candTail != currentTail
                }
            } else {
                emptyList()
            }

            if (qualified.size == 1) {
                val mirror = qualified.single()
                val mirrorNote = "镜像对冲命中：3分钟内不同本人账户出现唯一等额一出一进（${MoneyMath.formatUnsignedMinor(ai.amountMinor, ai.currency, ai.scale)}），自动归为内部流转。"
                val updatedMirror = mirror.copy(
                    direction = TxDirection.INTERNAL_TRANSFER,
                    category = "内部流转",
                    status = TxStatus.IGNORED,
                    ignoreReason = mirrorNote,
                    relatedTxId = payload.groupId,
                    updatedAtMs = now,
                    revision = mirror.revision + 1
                )
                mirrorUpdatedTx = updatedMirror
                direction = TxDirection.INTERNAL_TRANSFER
                category = "内部流转"
                status = TxStatus.IGNORED
                ignoreReason = mirrorNote
                reasoning = mirrorNote
                relatedTxId = mirror.id
                mirrorChangeToSave = TransactionChange(
                    opType = ChangeOpType.MARK_INTERNAL,
                    affectedTxIds = listOf(mirror.id, payload.groupId),
                    beforeRevisions = mapOf(mirror.id to mirror.revision, payload.groupId to 1),
                    afterRevisions = mapOf(mirror.id to updatedMirror.revision, payload.groupId to 1),
                    inversePayloadJson = JSONObject().apply {
                        put("transactions", JSONArray().apply {
                            put(BackupCodec.txToJson(mirror))
                            put(
                                BackupCodec.txToJson(
                                    Transaction(
                                        id = payload.groupId,
                                        occurredAtMs = payload.createdAtMs,
                                        observedAtMs = payload.observedAtMs,
                                        updatedAtMs = now,
                                        revision = 1,
                                        direction = ai.direction,
                                        amountMinor = ai.amountMinor,
                                        currency = ai.currency,
                                        scale = ai.scale,
                                        merchant = ai.merchant,
                                        itemSummary = ai.itemSummary,
                                        category = ai.category,
                                        paymentMethod = ai.paymentMethod,
                                        sourceChannels = payload.sourceChannels,
                                        mergedNotifications = payload.mergedNotifications,
                                        status = TxStatus.RECORDED,
                                        deepLink = payload.deepLink,
                                        deepLinkLabel = payload.deepLinkLabel,
                                        sanitizedText = payload.sanitizedText,
                                        aiModeUsed = ai.aiModeUsed,
                                        aiReasoning = ai.reasoning
                                    )
                                )
                            )
                        })
                    }.toString(),
                    createdAtMs = now
                )
                store.addLog(
                    "MIRROR_NETTING",
                    "镜像对冲生效！将【${mirror.paymentMethod}】与【${ai.paymentMethod}】两笔 ${MoneyMath.formatUnsignedMinor(ai.amountMinor, ai.currency, ai.scale)} 移出主账本"
                )
            } else if (qualified.size > 1) {
                // Ambiguous multiple candidates: do NOT auto-ignore; flag for review!
                status = TxStatus.NEEDS_REVIEW
                reasoning = "疑似本人互转（时间窗内存在 ${qualified.size} 个等额候选），未自动忽略，请人工核对。"
            }
        }

        // Refund matching (both normal order and refund-before-expense out-of-order back-linking)
        var backLinkedRefund: Transaction? = null
        if (direction == TxDirection.REFUND && ai.amountMinor > 0L) {
            val matched = store.findRecentExpenseForRefund(
                amountMinor = ai.amountMinor,
                currency = ai.currency,
                merchant = ai.merchant,
                sinceMs = payload.createdAtMs - 30L * 86400_000L
            )
            if (matched != null) relatedTxId = matched.id
        } else if (direction == TxDirection.EXPENSE && status == TxStatus.RECORDED && ai.amountMinor > 0L) {
            // Check if an unlinked refund arrived BEFORE the original expense notification
            val recentUnlinkedRefund = store.listTransactions(limit = 40).firstOrNull { tx ->
                tx.status == TxStatus.RECORDED &&
                    tx.direction == TxDirection.REFUND &&
                    tx.relatedTxId == null &&
                    tx.currency == ai.currency &&
                    tx.amountMinor <= ai.amountMinor &&
                    abs(payload.createdAtMs - tx.occurredAtMs) <= 30L * 86400_000L &&
                    ai.merchant != "未知商户" && tx.merchant != "未知商户" &&
                    (ai.merchant.contains(tx.merchant) || tx.merchant.contains(ai.merchant))
            }
            if (recentUnlinkedRefund != null) {
                backLinkedRefund = recentUnlinkedRefund.copy(
                    relatedTxId = payload.groupId,
                    updatedAtMs = now,
                    revision = recentUnlinkedRefund.revision + 1
                )
            }
        }

        // Settlement fields (for foreign currency estimated or confirmed RMB conversion)
        val settlementCand = payload.settlementCandidate
        val settlementAmtMinor: Long?
        val settlementCurr: String?
        val settlementScale: Int
        val settlementEst: Boolean
        if (settlementCand != null && settlementCand.currency != ai.currency) {
            settlementAmtMinor = settlementCand.amountMinor
            settlementCurr = settlementCand.currency
            settlementScale = settlementCand.scale
            settlementEst = settlementCand.isEstimated
        } else {
            settlementAmtMinor = null
            settlementCurr = null
            settlementScale = 2
            settlementEst = false
        }

        val primaryTx = Transaction(
            id = payload.groupId,
            occurredAtMs = payload.createdAtMs,
            observedAtMs = payload.observedAtMs,
            updatedAtMs = now,
            revision = 1,
            direction = direction,
            amountMinor = ai.amountMinor,
            currency = ai.currency,
            scale = ai.scale,
            settlementAmountMinor = settlementAmtMinor,
            settlementCurrency = settlementCurr,
            settlementScale = settlementScale,
            settlementIsEstimated = settlementEst,
            merchant = ai.merchant,
            itemSummary = ai.itemSummary,
            category = category,
            paymentMethod = ai.paymentMethod,
            sourceChannels = payload.sourceChannels,
            mergedNotifications = payload.mergedNotifications,
            status = status,
            ignoreReason = ignoreReason,
            deepLink = payload.deepLink,
            deepLinkLabel = payload.deepLinkLabel,
            sanitizedText = payload.sanitizedText,
            aiModeUsed = ai.aiModeUsed,
            aiReasoning = reasoning,
            relatedTxId = relatedTxId
        )

        val txsToCommit = mutableListOf(primaryTx)
        val linksToCommit = mutableListOf<TransactionSourceLink>()

        sources.forEachIndexed { idx, src ->
            val role = when {
                idx == 0 -> SourceLinkRole.PRIMARY_PAYMENT
                payload.splitParts.any { sp -> src.amountCandidates.any { it.amountMinor == sp.amountMinor } } -> SourceLinkRole.SPLIT_FUNDING
                else -> SourceLinkRole.CORROBORATING_CHANNEL
            }
            val candId = if (idx == 0) ai.selectedCandidateId else src.amountCandidates.firstOrNull { AmountCandidateExtractor.isRoleBookableAsPrimary(it.role) }?.candidateId
            linksToCommit.add(
                TransactionSourceLink(
                    transactionId = primaryTx.id,
                    sourceId = src.id,
                    linkRole = role,
                    candidateId = candId
                )
            )
        }

        // Separate FEE transaction sharing the exact same source_id (e.g. withdrawal principal + fee, or installment purchase + fee)
        val feeCand = payload.feeCandidateId?.let { fid -> payload.candidates.firstOrNull { it.candidateId == fid } }
        if (feeCand != null && feeCand.amountMinor > 0L && !payload.isZeroAuth && payload.ambiguityReason == null) {
            val feeTx = Transaction(
                id = "${payload.groupId}:fee",
                occurredAtMs = payload.createdAtMs,
                observedAtMs = payload.observedAtMs,
                updatedAtMs = now,
                revision = 1,
                direction = TxDirection.EXPENSE,
                amountMinor = feeCand.amountMinor,
                currency = feeCand.currency,
                scale = feeCand.scale,
                merchant = ai.merchant,
                itemSummary = "${ai.merchant} 手续费/服务费",
                category = "手续费",
                paymentMethod = ai.paymentMethod,
                sourceChannels = payload.sourceChannels,
                mergedNotifications = 1,
                status = TxStatus.RECORDED,
                deepLink = payload.deepLink,
                deepLinkLabel = payload.deepLinkLabel,
                sanitizedText = payload.sanitizedText,
                aiModeUsed = ai.aiModeUsed,
                aiReasoning = "与主交易(${primaryTx.direction.name})共享同一来源，手续费独立计为外部支出。",
                relatedTxId = primaryTx.id
            )
            txsToCommit.add(feeTx)
            linksToCommit.add(
                TransactionSourceLink(
                    transactionId = feeTx.id,
                    sourceId = sources.first().id,
                    linkRole = SourceLinkRole.FEE,
                    candidateId = feeCand.candidateId
                )
            )
        }

        val dbTxs = buildList {
            addAll(txsToCommit)
            if (mirrorUpdatedTx != null) add(mirrorUpdatedTx)
            if (backLinkedRefund != null) add(backLinkedRefund)
        }

        store.saveTransactionBundle(
            transactions = dbTxs,
            sources = sources,
            links = linksToCommit,
            change = mirrorChangeToSave
        )
        for (t in txsToCommit) {
            store.addLog(
                "AI_DECISION",
                "归档完成：[${t.direction}] ${MoneyMath.formatUnsignedMinor(t.amountMinor, t.currency, t.scale)} ${t.merchant} (${t.category}) -> ${t.status}",
                payload.sanitizedText
            )
        }
        return txsToCommit
    }

    private fun buildMergedPayload(group: BufferedGroup): MergedPayload {
        val sorted = group.clues.sortedByDescending { it.priority }
        val primary = sorted.first()

        var paymentMethod = ""
        for (c in sorted) {
            val t = "${c.raw.title} ${c.raw.content}"
            when {
                t.contains("花呗") -> { paymentMethod = "花呗"; break }
                t.contains("美团月付") -> { paymentMethod = "美团月付"; break }
                t.contains("抖音月付") -> { paymentMethod = "抖音月付"; break }
                t.contains("信用卡") -> {
                    paymentMethod = if (c.cardTail != null) "${c.raw.appName}(尾号${c.cardTail})" else "信用卡"
                    break
                }
            }
        }
        if (paymentMethod.isBlank()) {
            val bankClue = sorted.firstOrNull { it.raw.appName.contains("银行") && it.cardTail != null }
                ?: sorted.firstOrNull { it.raw.appName.contains("银行") }
                ?: sorted.firstOrNull { it.cardTail != null }
            paymentMethod = when {
                bankClue != null -> if (bankClue.cardTail != null) "${bankClue.raw.appName}(${bankClue.cardTail})" else bankClue.raw.appName
                primary.raw.content.contains("零钱") -> "微信零钱"
                primary.raw.content.contains("余额") && !primary.raw.content.contains("可用余额") -> "支付宝余额"
                else -> primary.raw.appName
            }
        }

        val mergedRaw = sorted.joinToString("\n") { c ->
            val merchant = notificationMerchant(c.raw)
            val hint = if (merchant == "未知商户") "" else " 商户：$merchant，"
            "[${c.raw.appName}/${c.raw.sourceType}] ${c.raw.title}: ${c.raw.content}$hint"
        }
        val (sanitized, redactionNotes) = PrivacySanitizer.sanitize(mergedRaw, profile)
        val preReason = sorted.firstNotNullOfOrNull { it.preInternalReason }
        val (deepLink, deepLinkLabel) = DEEP_LINKS[primary.raw.appPackage]
            ?: ("walletwatcher://detail" to "查看${primary.raw.appName}原账单")

        // Prefer the clue that holds explicit splitParts or feeCandidate or highest priority
        val richClue = sorted.firstOrNull { it.splitPartIds.isNotEmpty() || it.feeCandidateId != null || it.ambiguityReason != null }
            ?: primary
        val candMap = richClue.candidates.associateBy { it.candidateId }
        val splitCands = richClue.splitPartIds.mapNotNull { candMap[it] }
        val settlementCand = richClue.settlementCandidateId?.let { candMap[it] }

        val merchantOverride = sorted.firstNotNullOfOrNull { c ->
            c.raw.merchantOverride
                ?.trim()
                ?.takeIf { it.length >= 2 && it != "未知商户" }
                ?.let { PrivacySanitizer.sanitize(it, profile).first.trim() }
                ?.takeIf { it.length >= 2 && it != "未知商户" }
        }

        val localItemHint = sorted.firstNotNullOfOrNull { c ->
            c.raw.localItemHint
                ?.trim()
                ?.takeIf { it.length >= 2 }
                ?.let { PrivacySanitizer.sanitize(it, profile).first.trim() }
                ?.takeIf { it.length >= 2 }
        }

        return MergedPayload(
            groupId = group.groupId,
            createdAtMs = group.createdAtMs,
            observedAtMs = sorted.minOf { it.sourceRecord.observedAtMs },
            amountMinor = richClue.amountMinor ?: group.amountMinor,
            currency = richClue.currency,
            scale = richClue.scale,
            candidates = richClue.candidates,
            primaryCandidateId = richClue.primaryCandidateId,
            feeCandidateId = richClue.feeCandidateId,
            splitParts = splitCands,
            settlementCandidate = settlementCand,
            ambiguityReason = sorted.firstNotNullOfOrNull { it.ambiguityReason },
            roughDirection = group.roughDirection,
            paymentMethod = paymentMethod,
            sourceChannels = sorted.map { it.raw.appName }.distinct(),
            mergedNotifications = sorted.size,
            sanitizedText = sanitized,
            redactionNotes = redactionNotes,
            preInternalReason = preReason,
            isZeroAuth = sorted.any { it.isZeroAuth },
            deepLink = deepLink,
            deepLinkLabel = deepLinkLabel,
            sources = sorted.map { it.sourceRecord },
            merchantHintOverride = merchantOverride,
            localItemHint = localItemHint
        )
    }

    private fun isFinanceRelated(text: String): Boolean {
        if (FAILED_TX_KEYWORDS.any { text.contains(it) }) {
            return false
        }
        if (NOISE_BLACKLIST_KEYWORDS.any { text.contains(it) }) {
            val isRealVoucher = listOf(
                "微信支付凭证", "付款成功", "支付成功", "扣款成功", "已扣款",
                "入账", "到账人民币", "退款成功", "实付", "分期购机", "另收手续费"
            ).any { text.contains(it) }
            if (!isRealVoucher) return false
        }
        if (FINANCE_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
            return DIGIT_REGEX.containsMatchIn(text) || listOf("先用后付", "免密签约", "银证").any { text.contains(it) }
        }
        return false
    }

    private fun extractClue(notif: RawNotification): ParsedClue {
        val text = "${notif.title} ${notif.content}"
        val extraction = AmountCandidateExtractor.extract(text)
        val primaryCand = extraction.primaryCandidate
        val cardTail = CARD_TAIL_REGEX.find(text)?.groupValues?.getOrNull(1)
        val isZeroAuth = ZERO_AUTH_KEYWORDS.any { text.contains(it) } &&
            listOf("实际扣款", "已扣款", "扣款成功", "已完成扣款", "微信支付凭证", "付款成功", "支付成功").none { text.contains(it) } &&
            !Regex("""扣款[\d.,¥￥元\s]*成功""").containsMatchIn(text)

        val textNoPayer = text.replace("付款方", "").replace("付款人", "").replace("向你付款", "").replace("向您付款", "")
        val explicitOffset = listOf("AA返还", "AA收款", "朋友返还", "分摊返还", "报销", "差旅报销", "垫付返还").any { text.contains(it, ignoreCase = true) }
        val explicitIncome = listOf("向你付款", "向您付款", "收款到账", "收款成功", "已存入", "代发工资", "工资入账", "证券转银行").any { text.contains(it) }
        val roughDir = when {
            isZeroAuth -> "ZERO_AUTH"
            explicitOffset -> "OFFSET"
            listOf("退款", "已退回", "原路退回", "退货").any { text.contains(it) } -> "REFUND"
            explicitIncome -> "IN"
            listOf("收款", "收入", "入账", "到账", "收到", "工资", "代发", "转入", "分红", "股息").any { text.contains(it) } -> {
                if (listOf("付款", "支出", "消费", "扣款", "支取", "划扣", "代扣", "实付", "支付成功", "银行转证券", "提现", "分期购机", "垫付").any { textNoPayer.contains(it) }) "OUT" else "IN"
            }
            listOf("支出", "付款", "消费", "扣款", "支取", "划扣", "代扣", "实付", "支付", "转出", "转账", "提现", "还款", "银行转证券", "分期购机", "垫付").any { textNoPayer.contains(it) } -> "OUT"
            Regex("""(?:[¥￥]|RMB|CNY|[：:\s])\s*-\s*\d""").containsMatchIn(text) -> "OUT"
            Regex("""(?:[¥￥]|RMB|CNY|[：:\s])\s*\+\s*\d""").containsMatchIn(text) -> "IN"
            else -> "UNKNOWN"
        }

        var preInternal: String? = null
        val isFeeOnlyOrDividend = listOf("分红", "股息").any { text.contains(it) }
        if (!isFeeOnlyOrDividend) {
            for ((label, kws) in INTERNAL_KEYWORDS) {
                if (kws.any { text.contains(it) }) {
                    // Do not treat utility/phone/membership recharge as wallet internal recharge
                    if (label == "钱包充值/提现" && listOf("话费充值", "水费充值", "电费充值", "燃气充值", "油卡充值", "会员充值", "游戏充值").any { text.contains(it) }) {
                        continue
                    }
                    preInternal = "命中第一道过滤网【$label】，不计入日常收支。"
                    break
                }
            }
        }
        if (preInternal == null && !isFeeOnlyOrDividend && profile.brokers.any { text.contains(it) }) {
            val isMembershipOrPurchase = listOf("会员", "续费", "课程", "套餐", "商城", "软件").any { text.contains(it) }
            val hasTransferContext = listOf("转账", "存管", "转出", "转入", "划转", "证券", "保证金", "银证").any { text.contains(it) }
            if (!isMembershipOrPurchase && hasTransferContext) {
                preInternal = "交易对手匹配本地券商列表（银证资金划转），属于内部流转。"
            }
        }
        val names = listOf(profile.realName) + profile.aliases
        if (preInternal == null && names.filter { it.isNotBlank() }.any { name ->
                listOf("向", "给", "收款方：", "收款人：").any { text.contains(it + name) }
            }) {
            preInternal = "收款方匹配本人档案"
        }

        val priority = when {
            notif.appPackage == "com.tencent.mm" && notif.sourceType == "accessibility_page" -> 100
            notif.appPackage == "com.eg.android.AlipayGphone" && notif.sourceType == "accessibility_page" -> 100
            notif.appPackage == "com.tencent.mm" -> 90
            notif.appPackage == "com.eg.android.AlipayGphone" -> 90
            notif.appPackage == "com.unionpay" -> 70
            notif.sourceType == "sms" -> 40
            notif.appName.contains("银行") || notif.appPackage.lowercase().contains("bank") -> 60
            else -> 55
        }

        val merchantHint = notificationMerchant(notif)
        val fp = notif.dedupFingerprintOverride?.takeIf { it.isNotBlank() }
            ?: shortFingerprint(notif.appPackage, notif.sourceType, primaryCand?.amountMinor, primaryCand?.currency ?: "CNY", merchantHint, notif.timestampMs / 60_000L)
        val sourceRecord = TransactionSource(
            id = UUID.randomUUID().toString(),
            sourceType = notif.sourceType,
            appPackage = notif.appPackage,
            appName = notif.appName,
            externalId = notif.externalId,
            observedAtMs = nowProvider(),
            sourceEventTimeMs = notif.timestampMs,
            amountCandidates = extraction.candidates,
            matcherVersion = "v3-local-1",
            shortFingerprint = fp,
            cardTail = cardTail,
            merchantHint = if (merchantHint == "未知商户") "" else merchantHint,
            channelLabel = if (cardTail != null) "${notif.appName}($cardTail)" else notif.appName
        )

        return ParsedClue(
            raw = notif,
            amountMinor = primaryCand?.amountMinor,
            currency = primaryCand?.currency ?: "CNY",
            scale = primaryCand?.scale ?: 2,
            candidates = extraction.candidates,
            primaryCandidateId = primaryCand?.candidateId,
            feeCandidateId = extraction.feeCandidate?.candidateId,
            splitPartIds = extraction.splitParts.map { it.candidateId },
            settlementCandidateId = extraction.settlementCandidate?.candidateId,
            ambiguityReason = extraction.ambiguityReason ?: notif.ambiguityOverride,
            roughDirection = roughDir,
            priority = priority,
            cardTail = cardTail,
            isZeroAuth = isZeroAuth,
            preInternalReason = preInternal,
            sourceRecord = sourceRecord
        )
    }

    private fun shortFingerprint(
        pkg: String,
        sourceType: String,
        amountMinor: Long?,
        currency: String,
        merchant: String,
        minuteBucket: Long
    ): String {
        val raw = "$pkg|$sourceType|${amountMinor ?: -1}|$currency|$merchant|$minuteBucket"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun notificationMerchant(raw: RawNotification): String {
        val override = raw.merchantOverride?.trim()
        if (!override.isNullOrBlank() && override != "未知商户") return override
        val merchant = AiClient.extractMerchant(raw.content)
        if (merchant != "未知商户") return merchant
        val title = raw.title.trim()
        return if (title.endsWith("咖啡") || title.endsWith("便利店") || title.endsWith("超市")) title
        else AiClient.extractMerchant(title)
    }

    private fun findMatchingBuffer(clue: ParsedClue, nowMs: Long): BufferedGroup? {
        val amountMinor = clue.amountMinor ?: return null
        for (group in buffers.values) {
            if (abs(nowMs - group.createdAtMs) > dedupWindowMs) continue
            val gAmt = group.amountMinor ?: continue
            if (group.currency != clue.currency) continue

            val sameDir = group.roughDirection == clue.roughDirection ||
                group.roughDirection == "UNKNOWN" || clue.roughDirection == "UNKNOWN" ||
                (group.roughDirection == "REFUND" && clue.roughDirection == "IN") ||
                (group.roughDirection == "IN" && clue.roughDirection == "REFUND")
            if (!sameDir) continue

            // Check composite split-part match inside buffer (e.g., 25元 composite + 15元 bank alert)
            val isSplitPartMatch = gAmt > amountMinor && clue.raw.appPackage !in group.clues.map { it.raw.appPackage } &&
                group.clues.any { prior ->
                    prior.splitPartIds.any { sid ->
                        prior.candidates.any { it.candidateId == sid && it.amountMinor == amountMinor && it.currency == clue.currency }
                    }
                }
            if (isSplitPartMatch) {
                return group
            }

            if (gAmt == amountMinor) {
                if (!clue.raw.externalId.isNullOrBlank() &&
                    group.clues.any { !it.raw.externalId.isNullOrBlank() && it.raw.externalId != clue.raw.externalId }
                ) {
                    continue
                }
                if (clue.raw.appPackage == ScreenBillParser.PKG_WECHAT &&
                    group.clues.any { it.raw.appPackage == ScreenBillParser.PKG_ALIPAY }
                ) {
                    continue
                }
                if (clue.raw.appPackage == ScreenBillParser.PKG_ALIPAY &&
                    group.clues.any { it.raw.appPackage == ScreenBillParser.PKG_WECHAT }
                ) {
                    continue
                }
                if (group.clues.any { it.cardTail != null && clue.cardTail != null && it.cardTail != clue.cardTail }) continue
                val merchant = notificationMerchant(clue.raw)
                if (group.clues.any { prior ->
                    val other = notificationMerchant(prior.raw)
                    !isGenericMerchantName(merchant) && !isGenericMerchantName(other) &&
                        !merchant.contains(other) && !other.contains(merchant)
                }) continue
                val existingApps = group.clues.map { it.raw.appPackage }.toSet()
                val existingSources = group.clues.map { it.raw.appPackage to it.raw.sourceType }.toSet()
                val isSameAppNearDuplicate = group.clues.any { prior ->
                    prior.raw.appPackage == clue.raw.appPackage &&
                        (prior.raw.notificationId == clue.raw.notificationId ||
                            (prior.raw.fromSummary != clue.raw.fromSummary &&
                                abs(prior.raw.timestampMs - clue.raw.timestampMs) <= 3_000L &&
                                prior.raw.content == clue.raw.content))
                }
                if (clue.raw.appPackage !in existingApps ||
                    (clue.raw.appPackage to clue.raw.sourceType) !in existingSources ||
                    isSameAppNearDuplicate
                ) {
                    return group
                }
            }
        }
        return null
    }

    private fun extractOwnCardTail(paymentMethod: String, fallbackText: String = ""): String? {
        val combined = "$paymentMethod $fallbackText"
        return profile.ownCardTails.firstOrNull { tail ->
            val t = tail.trim()
            t.isNotEmpty() && Regex("""(?<!\d)${Regex.escape(t)}(?!\d)""").containsMatchIn(combined)
        }
    }

    companion object {
        private val DIGIT_REGEX = Regex("""\d""")
        private val CARD_TAIL_REGEX = Regex("""(?:(?:尾号|卡号)为?|\*{2,4})\s*(?:\d{8,15})?(\d{4})(?!\d)""")
        private val TRANSFER_SEMANTIC_WORDS = listOf("转入", "转出", "转账", "汇款", "跨行")

        private val FAILED_TX_KEYWORDS = listOf(
            "交易失败", "付款失败", "支付失败", "扣款失败", "转账失败", "还款失败", "退款失败",
            "余额不足", "交易关闭", "订单已取消", "支付已取消", "操作已撤销", "未扣款成功"
        )

        private val NOISE_BLACKLIST_KEYWORDS = listOf(
            "验证码", "动态密码", "校验码", "短信验证", "安全验证",
            "优惠券", "代金券", "神券", "消费券", "立减券", "立减金", "满减", "领券",
            "申领指南", "补贴申领", "达标可领", "预提取", "活动规则", "待领取", "可领取", "免费领",
            "红包雨", "抽奖", "立省", "降价", "预售", "秒杀", "点击领取", "立即领取", "去使用", "助力",
            "账单已出", "本期应还", "应还金额", "最低还款", "还款提醒", "待还款", "最后还款日"
        )

        private val FINANCE_KEYWORDS = listOf(
            "支付", "付款", "收款", "扣款", "支取", "划扣", "代扣", "实付", "消费", "转账",
            "支出", "收入", "入账", "到账", "退款", "退回", "还款", "提现", "充值",
            "银证", "证券", "余额宝", "零钱通", "花呗", "信用卡", "白条", "月付",
            "先用后付", "先享后付", "免密", "工资", "代发", "红包", "乘车码", "动账",
            "基金", "理财", "分红", "返还", "报销", "垫付", "分期购机", "手续费",
            "RMB", "CNY", "USD", "EUR", "GBP", "HKD", "JPY", "美元", "元", "¥", "￥", "$"
        )

        private val ZERO_AUTH_KEYWORDS = listOf(
            "0元下单", "0.00元下单", "已开通先用后付", "已使用先用后付",
            "先享后付已授权", "确认收货后自动扣款", "0元先享", "免密签约成功"
        )

        private val INTERNAL_KEYWORDS = listOf(
            "银证转账" to listOf("银证转账", "银行转证券", "证券转银行", "第三方存管", "资金存管"),
            "信用账单还款" to listOf("信用卡还款", "花呗还款", "白条还款", "月付还款", "自动还款", "账单还款", "分期还款"),
            "钱包充值/提现" to listOf("零钱提现", "余额提现", "提现至", "提现到", "零钱充值", "余额充值", "提现"),
            "理财/基金内部划转" to listOf("余额宝转入", "余额宝转出", "零钱通转入", "零钱通转出", "朝朝宝", "理财申购", "理财赎回", "货币基金申购", "基金赎回")
        )

        private val DEEP_LINKS = mapOf(
            "com.eg.android.AlipayGphone" to ("alipays://platformapi/startapp?appId=20000003" to "打开支付宝账单"),
            "com.tencent.mm" to ("walletwatcher://launch/com.tencent.mm" to "打开微信查账单"),
            "com.unionpay" to ("upwallet://" to "打开云闪付"),
            "com.sankuai.meituan" to ("imeituan://www.meituan.com/order/list" to "打开美团订单"),
            "com.taobao.taobao" to ("taobao://m.taobao.com/awp/base/orderlist.htm" to "打开淘宝订单"),
            "com.xunmeng.pinduoduo" to ("pinduoduo://com.xunmeng.pinduoduo/orders.html" to "打开拼多多订单")
        )
    }
}
