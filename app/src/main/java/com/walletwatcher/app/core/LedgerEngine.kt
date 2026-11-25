package com.walletwatcher.app.core

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class LedgerEngine(
    val store: LedgerStore,
    private val isNetworkAvailable: () -> Boolean = { true },
    var dedupWindowMs: Long = 10_000L,
    var mirrorWindowMs: Long = 180_000L
) {
    private val bufferLock = Any()
    private val buffers = LinkedHashMap<String, BufferedGroup>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

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

    fun updateAiConfig(newConfig: AiConfig) {
        aiConfig = newConfig
        store.saveAiConfig(newConfig)
        store.addLog("CONFIG", "AI 引擎已更新为：${newConfig.displayLabel}")
        notifyChanged()
    }

    fun ingestNotification(notif: RawNotification) {
        if (notif.isGroupSummary && notif.subMessages.isNotEmpty()) {
            store.addLog("UNPACK", "展开折叠通知（${notif.appName}，共 ${notif.subMessages.size} 条子消息）")
            notif.subMessages.forEachIndexed { idx, line ->
                ingestSingle(
                    notif.copy(
                        content = line,
                        timestampMs = notif.timestampMs + idx * 50L,
                        isGroupSummary = false,
                        subMessages = emptyList(),
                        notificationId = UUID.randomUUID().toString().take(10)
                    )
                )
            }
            return
        }
        ingestSingle(notif)
    }

    private fun ingestSingle(notif: RawNotification) {
        val combined = "${notif.title} ${notif.content}".trim()
        if (!isFinanceRelated(combined)) {
            return
        }

        val clue = extractClue(notif)
        val (previewSanitized, _) = PrivacySanitizer.sanitize(
            "[${notif.appName}] ${notif.title}: ${notif.content}",
            profile
        )
        store.addLog(
            "CAPTURE",
            "捕获动账通知：${notif.appName} | 预提金额=${clue.amount ?: "未知"} | 粗判=${clue.roughDirection}",
            previewSanitized
        )

        if (clue.isZeroAuth) {
            val group = BufferedGroup(
                groupId = UUID.randomUUID().toString().take(8),
                createdAtMs = notif.timestampMs,
                expiresAtMs = notif.timestampMs,
                amount = clue.amount,
                roughDirection = "ZERO_AUTH",
                clues = mutableListOf(clue)
            )
            processGroup(group)
            notifyChanged()
            return
        }

        synchronized(bufferLock) {
            val matched = findMatchingBuffer(clue, notif.timestampMs)
            if (matched != null) {
                matched.clues.add(clue)
                
                store.addLog(
                    "DEDUP_MERGE",
                    "防重合并命中！将【${notif.appName}】合入已有同笔缓冲（共 ${matched.clues.size} 条，¥${clue.amount}）"
                )
            } else {
                val gid = UUID.randomUUID().toString().take(8)
                buffers[gid] = BufferedGroup(
                    groupId = gid,
                    createdAtMs = notif.timestampMs,
                    expiresAtMs = notif.timestampMs + dedupWindowMs,
                    amount = clue.amount,
                    roughDirection = clue.roughDirection,
                    clues = mutableListOf(clue)
                )
            }
        }
        notifyChanged()
    }

    fun activeBufferCount(): Int = synchronized(bufferLock) { buffers.size }

    fun flushExpired(forceAll: Boolean = false, nowMs: Long = System.currentTimeMillis()): List<Transaction> {
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
            processGroup(group)?.let { created.add(it) }
        }
        notifyChanged()
        return created
    }

    @Synchronized
    fun replayOfflineQueue(): Int {
        if (!isNetworkAvailable()) return 0
        val queued = store.listOfflineQueue()
        var replayed = 0
        for (item in queued) {
            finalizePayload(item.payload)
            store.removeOfflineItem(item.queueId)
            replayed++
        }
        if (replayed > 0) {
            store.addLog("OFFLINE_REPLAY", "网络恢复！自动补发并入账 $replayed 笔断网暂存记录")
            notifyChanged()
        }
        return replayed
    }

    fun rescueIgnored(txId: String, newDirection: TxDirection = TxDirection.EXPENSE): Transaction? {
        val old = store.getTransaction(txId) ?: return null
        val rescued = old.copy(
            direction = newDirection,
            status = TxStatus.RECORDED,
            category = if (newDirection == TxDirection.EXPENSE) "日用" else "副业/其他收入",
            ignoreReason = "",
            rescuedByUser = true,
            aiReasoning = "[用户手动捞回主账本] ${old.aiReasoning}"
        )
        store.saveTransaction(rescued)
        store.addLog("USER_RESCUE", "手动将【${rescued.merchant} ¥${rescued.amount}】捞回主账本")
        notifyChanged()
        return rescued
    }

    fun getSummary(nowMs: Long = System.currentTimeMillis()): LedgerSummary {
        val all = store.listTransactions(limit = Int.MAX_VALUE)
        val recorded = all.filter { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW }
        val ignored = all.filter { it.status == TxStatus.IGNORED }

        val zone = java.time.ZoneId.systemDefault()
        val month = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(zone))
        val monthly = recorded.filter { java.time.YearMonth.from(java.time.Instant.ofEpochMilli(it.createdAtMs).atZone(zone)) == month }
        val grossExpense = round2(monthly.filter { it.direction == TxDirection.EXPENSE }.sumOf { it.amount })
        val totalRefund = round2(monthly.filter { it.direction == TxDirection.REFUND }.sumOf { it.amount })
        val netExpense = round2(grossExpense - totalRefund)
        val totalIncome = round2(monthly.filter { it.direction == TxDirection.INCOME }.sumOf { it.amount })
        val netBalance = round2(totalIncome - netExpense)

        return LedgerSummary(
            grossExpense = grossExpense,
            totalRefund = totalRefund,
            netExpense = netExpense,
            totalIncome = totalIncome,
            netBalance = netBalance,
            recordedCount = recorded.size,
            ignoredCount = ignored.size,
            offlineQueueCount = store.listOfflineQueue().size,
            dbSizeBytes = store.getDbSizeBytes()
        )
    }

    fun exportSanitizedDebugSamples(limit: Int = 40): String {
        val logs = store.listLogs(limit).filter { it.sanitizedSample.isNotBlank() }
        if (logs.isEmpty()) return "暂无已捕获的脱敏通知样本。"
        return buildString {
            appendLine("=== WalletWatcher 脱敏通知样本（已隐藏卡号/手机号/实名）===")
            for (log in logs) {
                appendLine("[${log.stage}] ${log.sanitizedSample}")
            }
        }
    }

    private fun processGroup(group: BufferedGroup): Transaction? {
        val payload = buildMergedPayload(group)
        if (payload.redactionNotes.isNotEmpty()) {
            store.addLog("SANITIZE", "隐私脱敏：${payload.redactionNotes.joinToString("、")}", payload.sanitizedText)
        }
        // Only queue offline if real API is enabled AND network is currently down
        // (Or in general whenever device is offline, queue safely for consistent offline resilience)
        if (aiConfig.isRealApiEnabled && !isNetworkAvailable()) {
            store.enqueueOffline(OfflineQueueItem(group.groupId, System.currentTimeMillis(), payload))
            store.addLog(
                "OFFLINE_QUEUE",
                "当前无网络，已将【${payload.sourceChannels.joinToString("+")} ¥${payload.amount ?: 0.0}】暂存本地队列"
            )
            return null
        }
        return try { finalizePayload(payload) } catch (e: java.io.IOException) {
            store.enqueueOffline(OfflineQueueItem(group.groupId, System.currentTimeMillis(), payload))
            null
        }
    }

    @Synchronized
    private fun finalizePayload(payload: MergedPayload): Transaction {
        val ai = AiClient.parse(aiConfig, profile, payload)

        var direction = ai.direction
        var category = ai.category
        var status = when {
            direction == TxDirection.INTERNAL_TRANSFER || direction == TxDirection.IGNORED -> TxStatus.IGNORED
            ai.needsReview -> TxStatus.NEEDS_REVIEW
            else -> TxStatus.RECORDED
        }
        var ignoreReason = if (status == TxStatus.IGNORED) ai.reasoning else ""
        var reasoning = ai.reasoning
        var relatedTxId: String? = null

        // Mirror transfer check: A account out X + B account in X within 3 minutes -> both internal
        if (status == TxStatus.RECORDED &&
            (direction == TxDirection.EXPENSE || direction == TxDirection.INCOME) &&
            ai.amount > 0.0
        ) {
            val opposite = if (direction == TxDirection.EXPENSE) TxDirection.INCOME else TxDirection.EXPENSE
            val sinceMs = payload.createdAtMs - mirrorWindowMs
            val mirror = store.findRecentMirrorCandidate(ai.amount, opposite, sinceMs)
            if (mirror != null && mirror.createdAtMs <= payload.createdAtMs && !mirror.rescuedByUser &&
                listOf("转入", "转出", "转账").any { mirror.sanitizedText.contains(it) } &&
                listOf("转入", "转出", "转账").any { payload.sanitizedText.contains(it) } &&
                profile.ownCardTails.any { mirror.paymentMethod.endsWith("($it)") } &&
                profile.ownCardTails.any { payload.paymentMethod.endsWith("($it)") && !mirror.paymentMethod.endsWith("($it)") }) {
                val mirrorNote = "镜像对冲命中：3分钟内不同账户出现等额一出一进（¥${String.format("%.2f", ai.amount)}），自动归为内部流转。"
                store.saveTransaction(
                    mirror.copy(
                        direction = TxDirection.INTERNAL_TRANSFER,
                        category = "内部流转",
                        status = TxStatus.IGNORED,
                        ignoreReason = mirrorNote,
                        relatedTxId = payload.groupId
                    )
                )
                direction = TxDirection.INTERNAL_TRANSFER
                category = "内部流转"
                status = TxStatus.IGNORED
                ignoreReason = mirrorNote
                reasoning = mirrorNote
                relatedTxId = mirror.id
                store.addLog(
                    "MIRROR_NETTING",
                    "镜像对冲生效！将【${mirror.paymentMethod}】与【${ai.paymentMethod}】两笔 ¥${ai.amount} 移出主账本"
                )
            }
        }

        // Refund matching
        if (direction == TxDirection.REFUND && ai.amount > 0.0) {
            val matched = store.findRecentExpenseForRefund(ai.amount, ai.merchant, payload.createdAtMs - 30L * 86400_000L)
            if (matched != null) {
                relatedTxId = matched.id
            }
        }

        val tx = Transaction(
            id = payload.groupId,
            createdAtMs = payload.createdAtMs,
            direction = direction,
            amount = ai.amount,
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
        store.saveTransaction(tx)
        store.addLog(
            "AI_DECISION",
            "归档完成：[${tx.direction}] ¥${String.format("%.2f", tx.amount)} ${tx.merchant} (${tx.category}) -> ${tx.status}",
            payload.sanitizedText
        )
        return tx
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
                primary.raw.content.contains("余额") -> "支付宝余额"
                else -> primary.raw.appName
            }
        }

        val mergedRaw = sorted.joinToString("\n") { c ->
            "[${c.raw.appName}/${c.raw.sourceType}] ${c.raw.title}: ${c.raw.content}"
        }
        val (sanitized, redactionNotes) = PrivacySanitizer.sanitize(mergedRaw, profile)
        val preReason = sorted.firstNotNullOfOrNull { it.preInternalReason }
        val (deepLink, deepLinkLabel) = DEEP_LINKS[primary.raw.appPackage]
            ?: ("walletwatcher://detail" to "查看${primary.raw.appName}原账单")

        return MergedPayload(
            groupId = group.groupId,
            createdAtMs = group.createdAtMs,
            amount = group.amount,
            roughDirection = group.roughDirection,
            paymentMethod = paymentMethod,
            sourceChannels = sorted.map { it.raw.appName }.distinct(),
            mergedNotifications = sorted.size,
            sanitizedText = sanitized,
            redactionNotes = redactionNotes,
            preInternalReason = preReason,
            isZeroAuth = sorted.any { it.isZeroAuth },
            deepLink = deepLink,
            deepLinkLabel = deepLinkLabel
        )
    }

    private fun isFinanceRelated(text: String): Boolean {
        if (FINANCE_KEYWORDS.any { text.contains(it) }) {
            return DIGIT_REGEX.containsMatchIn(text) || listOf("先用后付", "免密签约", "银证").any { text.contains(it) }
        }
        return false
    }

    private fun extractClue(notif: RawNotification): ParsedClue {
        val text = "${notif.title} ${notif.content}"
        val amount = extractAmount(text)
        val cardTail = CARD_TAIL_REGEX.find(text)?.groupValues?.getOrNull(1)
        val isZeroAuth = ZERO_AUTH_KEYWORDS.any { text.contains(it) } &&
            listOf("实际扣款", "已扣款", "扣款成功", "已完成扣款").none { text.contains(it) }

        val roughDir = when {
            isZeroAuth -> "ZERO_AUTH"
            listOf("退款", "已退回", "原路退回", "退货").any { text.contains(it) } -> "REFUND"
            listOf("收款", "收入", "入账", "到账", "收到", "工资", "代发", "转入", "证券转银行").any { text.contains(it) } -> {
                if (listOf("付款", "支出", "消费", "扣款", "支付成功", "银行转证券").any { text.contains(it) }) "OUT" else "IN"
            }
            listOf("支出", "付款", "消费", "扣款", "支付", "转出", "转账", "提现", "还款", "银行转证券").any { text.contains(it) } -> "OUT"
            else -> "UNKNOWN"
        }

        var preInternal: String? = null
        for ((label, kws) in INTERNAL_KEYWORDS) {
            if (kws.any { text.contains(it) }) {
                preInternal = "命中第一道过滤网【$label】，不计入日常收支。"
                break
            }
        }
        if (preInternal == null && profile.brokers.any { text.contains(it) }) {
            preInternal = "交易对手匹配本地券商列表（银证资金划转），属于内部流转。"
        }
        val names = listOf(profile.realName) + profile.aliases
        if (preInternal == null && names.filter { it.isNotBlank() }.any { name ->
                listOf("向", "给", "收款方：", "收款人：").any { text.contains(it + name) }
            }) preInternal = "收款方匹配本人档案"
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

        return ParsedClue(
            raw = notif,
            amount = amount,
            roughDirection = roughDir,
            priority = priority,
            cardTail = cardTail,
            isZeroAuth = isZeroAuth,
            preInternalReason = preInternal
        )
    }

    private fun extractAmount(text: String): Double? {
        var cleaned = text.replace(Regex("""(?:尾号|卡号)\s*\d{4}"""), "")
        cleaned = cleaned.replace(Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?"""), "")
        cleaned = cleaned.replace(Regex("""\d{1,2}:\d{2}(?::\d{2})?"""), "")
        cleaned = cleaned.replace(Regex("""(?:可用余额|余额|额度)[：: ]*[¥￥]?\s*[\d,.]+元?"""), "")
        for (pat in AMOUNT_PATTERNS) {
            val m = pat.find(cleaned) ?: continue
            val parsed = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (parsed != null) return round2(parsed)
        }
        return null
    }

    private fun findMatchingBuffer(clue: ParsedClue, nowMs: Long): BufferedGroup? {
        val amount = clue.amount ?: return null
        for (group in buffers.values) {
            if (abs(nowMs - group.createdAtMs) > dedupWindowMs) continue
            val gAmt = group.amount ?: continue
            if (abs(gAmt - amount) < 0.005) {
                val sameDir = group.roughDirection == clue.roughDirection ||
                    group.roughDirection == "UNKNOWN" || clue.roughDirection == "UNKNOWN"
                if (!sameDir) continue

                if (group.clues.any { it.cardTail != null && clue.cardTail != null && it.cardTail != clue.cardTail }) continue
                val merchant = AiClient.extractMerchant(clue.raw.content)
                if (group.clues.any { prior ->
                    val other = AiClient.extractMerchant(prior.raw.content)
                    merchant != "未知商户" && other != "未知商户" && !merchant.contains(other) && !other.contains(merchant)
                }) continue
                val existingApps = group.clues.map { it.raw.appPackage }.toSet()
                val existingSources = group.clues.map { it.raw.appPackage to it.raw.sourceType }.toSet()
                if (clue.raw.appPackage !in existingApps ||
                    (clue.raw.appPackage to clue.raw.sourceType) !in existingSources ||
                    group.clues.any { it.raw.notificationId == clue.raw.notificationId && it.raw.content == clue.raw.content }
                ) {
                    return group
                }
            }
        }
        return null
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToLong() / 100.0

    companion object {
        private val DIGIT_REGEX = Regex("""\d""")
        private val CARD_TAIL_REGEX = Regex("""(?:尾号|卡号|\*{2,4})\s*(?:\d{8,15})?(\d{4})(?!\d)""")

        private val AMOUNT_PATTERNS = listOf(
            Regex("""[¥￥]\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),
            Regex("""(?:人民币|金额|支出|收入|消费|扣款|付款|收款|转出|转入|入账|到账|退款|退回|还款|提现|充值|转账|为|共|计)[：:\s]*(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元?"""),
            Regex("""(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元""")
        )

        private val FINANCE_KEYWORDS = listOf(
            "支付", "付款", "收款", "扣款", "消费", "转账", "支出", "收入", "入账", "到账",
            "退款", "退回", "还款", "提现", "充值", "银证", "证券", "余额宝", "零钱通",
            "花呗", "信用卡", "白条", "月付", "先用后付", "先享后付", "免密", "工资", "代发",
            "红包", "乘车码", "动账", "基金", "理财", "分红", "元", "¥", "￥"
        )

        private val ZERO_AUTH_KEYWORDS = listOf(
            "0元下单", "0.00元下单", "已开通先用后付", "已使用先用后付",
            "先享后付已授权", "确认收货后自动扣款", "0元先享", "免密签约成功"
        )

        private val INTERNAL_KEYWORDS = listOf(
            "银证转账" to listOf("银证转账", "银行转证券", "证券转银行", "第三方存管", "资金存管"),
            "信用账单还款" to listOf("信用卡还款", "花呗还款", "白条还款", "月付还款", "自动还款", "账单还款"),
            "钱包充值/提现" to listOf("零钱提现", "余额提现", "提现至", "提现到", "零钱充值", "余额充值"),
            "理财/基金内部划转" to listOf("余额宝转入", "余额宝转出", "零钱通转入", "零钱通转出", "朝朝宝", "理财申购", "理财赎回", "货币基金申购", "基金赎回")
        )

        private val DEEP_LINKS = mapOf(
            "com.eg.android.AlipayGphone" to ("alipays://platformapi/startapp?appId=20000003" to "打开支付宝账单"),
            "com.tencent.mm" to ("weixin://dl/wallet" to "打开微信账单"),
            "com.unionpay" to ("upwallet://" to "打开云闪付"),
            "com.sankuai.meituan" to ("imeituan://www.meituan.com/order/list" to "打开美团订单"),
            "com.taobao.taobao" to ("taobao://m.taobao.com/awp/base/orderlist.htm" to "打开淘宝订单"),
            "com.xunmeng.pinduoduo" to ("pinduoduo://com.xunmeng.pinduoduo/orders.html" to "打开拼多多订单")
        )
    }
}

