package com.walletwatcher.app

import com.walletwatcher.app.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LedgerEngineCoreTest {

    private class InMemoryLedgerStore(
        private var profile: UserProfile = UserProfile(
            realName = "林晓明",
            aliases = listOf("小明"),
            ownCardTails = listOf("6288", "8841", "3092")
        ),
        private var aiConfig: AiConfig = AiConfig(mode = AiConfig.MODE_LOCAL)
    ) : LedgerStore {
        private val storeLock = Any()
        private val txs = LinkedHashMap<String, Transaction>()
        private val sources = LinkedHashMap<String, TransactionSource>()
        private val links = mutableListOf<TransactionSourceLink>()
        private val changes = LinkedHashMap<String, TransactionChange>()
        private val rules = LinkedHashMap<String, UserMerchantRule>()
        private val queue = LinkedHashMap<String, OfflineQueueItem>()
        private val logs = mutableListOf<AuditLogEntry>()

        override fun saveTransaction(tx: Transaction) = synchronized(storeLock) { txs[tx.id] = tx }

        override fun saveTransactionBundle(
            transactions: List<Transaction>,
            sources: List<TransactionSource>,
            links: List<TransactionSourceLink>,
            change: TransactionChange?
        ) = synchronized(storeLock) {
            for (s in sources) this.sources[s.id] = s
            for (t in transactions) this.txs[t.id] = t
            for (l in links) {
                this.links.removeAll { it.transactionId == l.transactionId && it.sourceId == l.sourceId && it.linkRole == l.linkRole }
                this.links.add(l)
            }
            if (change != null) changes[change.opId] = change
        }

        override fun getTransaction(txId: String): Transaction? = synchronized(storeLock) { txs[txId] }

        override fun listTransactions(limit: Int): List<Transaction> = synchronized(storeLock) {
            txs.values.sortedByDescending { it.occurredAtMs }.take(limit)
        }

        override fun listSourcesForTransaction(txId: String): List<Pair<TransactionSource, TransactionSourceLink>> = synchronized(storeLock) {
            links.filter { it.transactionId == txId }.mapNotNull { l ->
                sources[l.sourceId]?.let { it to l }
            }
        }

        override fun listLinksForSource(sourceId: String): List<TransactionSourceLink> = synchronized(storeLock) {
            links.filter { it.sourceId == sourceId }
        }

        override fun hasActiveSourceExternalId(externalId: String): Boolean = synchronized(storeLock) {
            if (externalId.isBlank()) return@synchronized false
            val matchingSourceIds = sources.values.filter { it.externalId == externalId }.map { it.id }.toSet()
            if (matchingSourceIds.isEmpty()) return@synchronized false
            links.any { l ->
                l.sourceId in matchingSourceIds && txs[l.transactionId]?.status != TxStatus.REVERTED
            }
        }

        override fun hasActiveSourceFingerprint(shortFingerprint: String): Boolean = synchronized(storeLock) {
            if (shortFingerprint.isBlank()) return@synchronized false
            val matchingSourceIds = sources.values.filter { it.shortFingerprint == shortFingerprint }.map { it.id }.toSet()
            if (matchingSourceIds.isEmpty()) return@synchronized false
            links.any { l ->
                l.sourceId in matchingSourceIds && txs[l.transactionId]?.status != TxStatus.REVERTED
            }
        }

        override fun findBackfillCandidates(
            amountMinor: Long,
            currency: String,
            sinceMs: Long,
            untilMs: Long
        ): List<Transaction> = synchronized(storeLock) {
            txs.values
                .filter {
                    (it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW) &&
                        it.currency == currency &&
                        it.amountMinor == amountMinor &&
                        it.occurredAtMs in sinceMs..untilMs
                }
                .sortedByDescending { it.occurredAtMs }
        }

        override fun listAllSources(): List<TransactionSource> = synchronized(storeLock) { sources.values.toList() }
        override fun listAllLinks(): List<TransactionSourceLink> = synchronized(storeLock) { links.toList() }
        override fun listChanges(limit: Int): List<TransactionChange> = synchronized(storeLock) { changes.values.reversed().take(limit) }
        override fun getChange(opId: String): TransactionChange? = synchronized(storeLock) { changes[opId] }
        override fun saveChange(change: TransactionChange) = synchronized(storeLock) { changes[change.opId] = change }
        override fun deleteLinksForTransaction(txId: String) = synchronized(storeLock) { links.removeAll { it.transactionId == txId }; Unit }
        override fun saveLinks(links: List<TransactionSourceLink>) = synchronized(storeLock) {
            for (l in links) {
                this.links.removeAll { it.transactionId == l.transactionId && it.sourceId == l.sourceId && it.linkRole == l.linkRole }
                this.links.add(l)
            }
        }

        override fun listUserRules(): List<UserMerchantRule> = synchronized(storeLock) { rules.values.toList() }
        override fun saveUserRule(rule: UserMerchantRule) = synchronized(storeLock) { rules[rule.id] = rule }
        override fun deleteUserRule(ruleId: String) = synchronized(storeLock) { rules.remove(ruleId); Unit }

        override fun findRecentMirrorCandidates(
            amountMinor: Long,
            currency: String,
            oppositeDirection: TxDirection,
            sinceMs: Long,
            untilMs: Long
        ): List<Transaction> = synchronized(storeLock) {
            txs.values.filter {
                it.status == TxStatus.RECORDED &&
                    it.direction == oppositeDirection &&
                    it.currency == currency &&
                    it.amountMinor == amountMinor &&
                    it.occurredAtMs in sinceMs..untilMs
            }
        }

        override fun findRecentExpenseForRefund(
            amountMinor: Long,
            currency: String,
            merchant: String,
            sinceMs: Long
        ): Transaction? = synchronized(storeLock) {
            txs.values
                .filter {
                    it.status == TxStatus.RECORDED &&
                        it.direction == TxDirection.EXPENSE &&
                        it.currency == currency &&
                        it.amountMinor >= amountMinor &&
                        it.occurredAtMs >= sinceMs
                }
                .maxByOrNull { it.occurredAtMs }
        }

        override fun findRecentSplitFundedCandidate(
            partAmountMinor: Long,
            currency: String,
            sinceMs: Long,
            untilMs: Long
        ): Pair<Transaction, TransactionSource>? = synchronized(storeLock) {
            val candidates = txs.values.filter {
                (it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW) &&
                    it.direction == TxDirection.EXPENSE &&
                    it.currency == currency &&
                    it.amountMinor > partAmountMinor &&
                    it.occurredAtMs in sinceMs..untilMs
            }.sortedByDescending { it.occurredAtMs }
            for (tx in candidates) {
                val paired = listSourcesForTransaction(tx.id)
                val usedIds = paired.filter { it.second.linkRole == SourceLinkRole.SPLIT_FUNDING }
                    .mapNotNull { it.second.candidateId }.toSet()
                for ((src, _) in paired) {
                    val found = src.amountCandidates.firstOrNull { c ->
                        c.role == AmountRole.SPLIT_PART &&
                            c.currency == currency &&
                            c.amountMinor == partAmountMinor &&
                            c.candidateId !in usedIds
                    }
                    if (found != null) return@synchronized tx to src
                }
            }
            null
        }

        override fun enqueueOffline(item: OfflineQueueItem) = synchronized(storeLock) { queue[item.queueId] = item }
        override fun listOfflineQueue(): List<OfflineQueueItem> = synchronized(storeLock) { queue.values.sortedBy { it.enqueuedAtMs } }
        override fun removeOfflineItem(queueId: String) = synchronized(storeLock) { queue.remove(queueId); Unit }
        override fun addLog(stage: String, message: String, sanitizedSample: String) = synchronized(storeLock) {
            logs.add(AuditLogEntry(logs.size.toLong() + 1L, System.currentTimeMillis(), stage, message, sanitizedSample))
            Unit
        }
        override fun listLogs(limit: Int): List<AuditLogEntry> = synchronized(storeLock) { logs.reversed().take(limit) }
        override fun loadProfile(): UserProfile = synchronized(storeLock) { profile }
        override fun saveProfile(profile: UserProfile) = synchronized(storeLock) { this.profile = profile }
        override fun loadAiConfig(): AiConfig = synchronized(storeLock) { aiConfig }
        override fun saveAiConfig(config: AiConfig) = synchronized(storeLock) { this.aiConfig = config }
        override fun getDbSizeBytes(): Long = 4096L
        override fun clearLedgerAndLogs() = synchronized(storeLock) {
            txs.clear(); sources.clear(); links.clear(); changes.clear(); queue.clear(); logs.clear()
        }

        override fun <T> runInTransaction(block: () -> T): T = synchronized(storeLock) { block() }

        override fun exportLogicalSnapshot(nowMs: Long): BackupSnapshot {
            val txList = txs.values.toList()
            return BackupSnapshot(
                schemaVersion = BackupCodec.SNAPSHOT_SCHEMA_VERSION,
                exportedAtMs = nowMs,
                profile = profile,
                userRules = rules.values.toList(),
                transactions = txList,
                sources = sources.values.toList(),
                sourceLinks = links.toList(),
                changes = changes.values.toList(),
                pendingQueue = queue.values.toList(),
                currencyChecks = BackupCodec.computeCurrencyChecks(txList)
            )
        }

        override fun restoreFromSnapshot(
            snapshot: BackupSnapshot,
            beforeSwapHook: (() -> Unit)?,
            duringSwapHook: (() -> Unit)?
        ): RestoreReport {
            beforeSwapHook?.invoke()
            duringSwapHook?.invoke()
            clearLedgerAndLogs()
            profile = snapshot.profile
            for (r in snapshot.userRules) rules[r.id] = r
            for (s in snapshot.sources) sources[s.id] = s
            for (t in snapshot.transactions) txs[t.id] = t
            links.addAll(snapshot.sourceLinks)
            for (c in snapshot.changes) changes[c.opId] = c
            for (q in snapshot.pendingQueue) queue[q.queueId] = q
            return RestoreReport(
                transactionCount = snapshot.transactions.size,
                sourceCount = snapshot.sources.size,
                linkCount = snapshot.sourceLinks.size,
                changeCount = snapshot.changes.size,
                pendingQueueCount = snapshot.pendingQueue.size,
                currencyChecks = snapshot.currencyChecks
            )
        }
    }

    // ==================== Existing Core Regression Tests ====================

    @Test
    fun coffeeExpenseAndSalaryWithSanitization() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款19.90元，商品：生椰拿铁"))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尊敬的林晓明，您尾号6288储蓄卡代发工资入账12800.00元，手机13812345678，卡号6225880112346288"))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(1990L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(1280000L, summary.byCurrency["CNY"]!!.totalIncomeMinor)

        val salary = engine.store.listTransactions().first { it.direction == TxDirection.INCOME }
        assertFalse(salary.sanitizedText.contains("13812345678"))
        assertFalse(salary.sanitizedText.contains("6225880112346288"))
    }

    @Test
    fun tripleChannelDedupMergesIntoOneTransaction() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = System.currentTimeMillis()
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向蜜雪冰城付款12.00元，商品：柠檬水", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288储蓄卡消费12.00元，财付通-蜜雪冰城", t0 + 800L))
        engine.ingestNotification(RawNotification("com.unionpay", "云闪付", "交易通知", "卡号6225880112346288消费12.00元", t0 + 1500L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(1, summary.recordedCount)
        assertEquals(1200L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        val tx = store.listTransactions().single()
        assertEquals(3, tx.mergedNotifications)
        assertEquals(3, store.listSourcesForTransaction(tx.id).size)
        assertEquals("蜜雪冰城", tx.merchant)
        assertTrue(tx.paymentMethod.contains("招商银行"))
    }

    @Test
    fun payLaterZeroAuthThenCreditCardThenRepay() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付分", "淘宝0元下单，已使用先用后付，确认收货后自动扣款199.00元"))
        engine.flushExpired(forceAll = true)
        assertEquals(0, engine.getSummary().recordedCount)
        assertEquals(1, engine.getSummary().ignoredCount)

        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "淘宝先用后付确认收货自动扣款199.00元，招商银行信用卡尾号8841"))
        engine.flushExpired(forceAll = true)
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "还款成功", "尾号6288储蓄卡完成信用卡还款199.00元"))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(1, summary.recordedCount)
        assertEquals(19900L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(2, summary.ignoredCount)
    }

    @Test
    fun securitiesAndSelfTransfersAreIgnoredIncludingMirrorNetting() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = System.currentTimeMillis()
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "银证转账", "尾号6288银行转证券向华泰证券第三方存管转出10000.00元", t0))
        engine.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账短信", "尾号3092证券转银行入账8500.00元，东方财富", t0 + 1000L, "sms"))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "转账成功", "尾号6288储蓄卡向林晓明转账2000.00元", t0 + 2000L))
        engine.flushExpired(forceAll = true)

        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尾号6288网银转出5000.00元", t0 + 20_000L))
        engine.flushExpired(forceAll = true)
        engine.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账通知", "尾号3092跨行转入到账5000.00元", t0 + 45_000L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(0, summary.recordedCount)
        assertEquals(0L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(0L, summary.byCurrency["CNY"]!!.totalIncomeMinor)
        assertEquals(5, summary.ignoredCount)
    }

    @Test
    fun refundNetsOutExpenseAndOfflineQueueReplays() {
        var online = true
        val engine = LedgerEngine(InMemoryLedgerStore(), isNetworkAvailable = { online })
        val t0 = System.currentTimeMillis()
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向淘宝付款129.00元", t0))
        engine.flushExpired(forceAll = true)
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "退款通知", "淘宝退款129.00元已原路退回", t0 + 20_000L))
        engine.flushExpired(forceAll = true)

        assertEquals(0L, engine.getSummary().byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(0L, engine.getSummary().byCurrency["CNY"]!!.totalIncomeMinor)

        engine.updateAiConfig(AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, apiKey = "fixture"))
        online = false
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "深圳地铁乘车码免密扣款4.00元", t0 + 50_000L))
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary().offlineQueueCount)

        online = true
        engine.updateAiConfig(AiConfig(mode = AiConfig.MODE_LOCAL))
        val replayed = engine.replayOfflineQueue()
        assertEquals(1, replayed)
        assertEquals(0, engine.getSummary().offlineQueueCount)
        assertEquals(400L, engine.getSummary().byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun sameAmountDifferentMerchantsAndRefundAcrossMonths() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val time = 1700000000000L
        engine.ingestNotification(RawNotification("a", "微信", "付款", "向甲商店付款20元", time))
        engine.ingestNotification(RawNotification("b", "支付宝", "付款", "向乙商店付款20元", time + 500))
        engine.flushExpired(true)
        assertEquals(4000L, engine.getSummary(time).byCurrency["CNY"]!!.netPersonalExpenseMinor)
        val later = time + 32L * 86400000
        engine.ingestNotification(RawNotification("a", "微信", "退款", "甲商店退款20元", later))
        engine.flushExpired(true)
        assertEquals(-2000L, engine.getSummary(later).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun masksFormattedNumbersAndName() {
        val masked = PrivacySanitizer.sanitize("林晓明 138-1234-5678，卡 6225 8801 1234 6288", UserProfile(realName = "林晓明")).first
        assertFalse(masked.contains("林晓明"))
        assertFalse(masked.contains("1234"))
        assertTrue(masked.contains("****6288"))
    }

    @Test
    fun missingApiKeyFlagsNeedsReviewExplicitly() {
        val store = InMemoryLedgerStore(
            aiConfig = AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, apiKey = "")
        )
        val engine = LedgerEngine(store)
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款19.90元"))
        engine.flushExpired(forceAll = true)

        val tx = engine.store.listTransactions().first()
        assertEquals("missing_api_key", tx.aiModeUsed)
        assertEquals(TxStatus.NEEDS_REVIEW, tx.status)
    }

    @Test
    fun clearAllDataDropsInMemoryBuffersToo() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款18.00元"))
        assertEquals(1, engine.activeBufferCount())
        engine.clearAllData()
        assertEquals(0, engine.activeBufferCount())
        engine.flushExpired(forceAll = true)
        assertEquals(0, engine.getSummary().recordedCount)
    }

    @Test
    fun wechatChildAndGroupSummaryDoNotDoubleCount() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(
            RawNotification(
                appPackage = "com.tencent.mm",
                appName = "微信",
                title = "微信支付",
                content = "微信支付凭证：向蜜雪冰城付款12.00元",
                timestampMs = t0,
                notificationId = "child-key-1"
            )
        )
        engine.ingestNotification(
            RawNotification(
                appPackage = "com.tencent.mm",
                appName = "微信",
                title = "微信支付",
                content = "[2条] 微信支付",
                timestampMs = t0 + 80L,
                isGroupSummary = true,
                subMessages = listOf("微信支付凭证：向蜜雪冰城付款12.00元"),
                notificationId = "summary-key-1"
            )
        )
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(1200L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun payLaterActualChargeWithConfirmWordingIsRecorded() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：拼多多先用后付确认收货后自动扣款59.00元成功", t0)
        )
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(5900L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun otpPromoAndBillRemindersAreFilteredOut() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.android.mms", "短信", "招商银行", "您的支付验证码为482910，5分钟内有效", t0))
        engine.ingestNotification(RawNotification("com.sankuai.meituan", "美团", "美团外卖", "送您20元外卖神券已到账，点击领取去使用", t0 + 100L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "账单提醒", "您本月信用卡账单已出，本期应还3200.00元，最后还款日25日", t0 + 200L))
        engine.flushExpired(forceAll = true)

        assertEquals(0, engine.getSummary(t0).recordedCount)
        assertEquals(0, engine.getSummary(t0).ignoredCount)
    }

    @Test
    fun brokerMembershipFeeIsNotMistakenForSecuritiesTransfer() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：同花顺会员自动续费扣款28.00元", t0))
        engine.flushExpired(forceAll = true)

        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(2800L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun bankRmbWithoutYuanAndCardNumberAreParsedAccurately() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "动账提醒", "您卡号为6225880112346288于11月25日16:20消费RMB 35.50，可用余额RMB 1234.56", t0)
        )
        engine.ingestNotification(
            RawNotification("com.chinamworld.bocmbci", "中国银行", "动账短信", "您尾号3092借记卡11月25日支取人民币50.00元，余额900.00元", t0 + 100L, "sms")
        )
        engine.flushExpired(forceAll = true)

        assertEquals(2, engine.getSummary(t0).recordedCount)
        assertEquals(8550L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun refundToBankCardMergesIntoSingleRefundAndDoesNotInflateIncome() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向美团外卖付款38.00元", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288消费38.00元，商户：财付通-美团", t0 + 500L))
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(3800L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "退款通知", "美团外卖退款38.00元已退回招商银行尾号6288", t0 + 30_000L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡入账人民币38.00元，对方：财付通-美团", t0 + 31_000L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary(t0)
        assertEquals(2, summary.recordedCount)
        assertEquals(0L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(0L, summary.byCurrency["CNY"]!!.totalIncomeMinor)
    }

    @Test
    fun outOfOrderMirrorTransferStillNetsOut() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账通知", "尾号3092跨行转入到账5000.00元", t0 + 2000L))
        engine.flushExpired(forceAll = true)
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账短信", "尾号6288网银转出5000.00元", t0, "sms"))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary(t0)
        assertEquals(0, summary.recordedCount)
        assertEquals(2, summary.ignoredCount)
    }

    @Test
    fun incomeMentioningPayerAndSignedAmountsAreParsedCorrectly() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：收款200.00元，付款方：王凯", t0))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "收款提醒", "王凯通过扫码向你付款+50.00元，已存入余额", t0 + 100L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡支出 -35.50元，商户：星巴克", t0 + 200L))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向中国移动话费充值100.00元", t0 + 300L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "交易提醒", "您尾号6288储蓄卡交易失败（余额不足），交易金额399.00元", t0 + 400L))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "扣款通知", "自动扣款失败，未能扣除腾讯视频会员15.00元", t0 + 500L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary(t0)
        assertEquals(4, summary.recordedCount)
        assertEquals(25000L, summary.byCurrency["CNY"]!!.totalIncomeMinor)
        assertEquals(13550L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(0, summary.ignoredCount)
    }

    @Test
    fun separateSwipesAndUpdatedNotificationKeys() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val first = RawNotification("wallet", "钱包", "瑞幸咖啡", "付款成功 ¥19.90", notificationId = "one")
        engine.ingestNotification(first)
        engine.ingestNotification(first.copy(content = "付款成功 ¥19.90 使用银行卡"))
        engine.ingestNotification(first.copy(notificationId = "two"))
        engine.ingestNotification(first.copy(appPackage = "bank", title = "邻家便利店"))
        val rows = engine.flushExpired(true)
        assertEquals(3, rows.size)
        assertTrue(rows.any { it.merchant == "瑞幸咖啡" })
    }

    @Test
    fun interruptedBufferRecoversOnceAndUsesConvertedAmount() {
        val store = InMemoryLedgerStore()
        val first = LedgerEngine(store)
        first.ingestNotification(RawNotification("bank", "银行", "消费", "消费10美元，折合人民币72.30元"))
        assertEquals(0, first.replayOfflineQueue())
        val restarted = LedgerEngine(store)
        assertEquals(1, restarted.replayOfflineQueue())
        assertEquals(0, restarted.replayOfflineQueue())
        val tx = store.listTransactions().single()
        assertEquals("CNY", tx.currency)
        assertEquals(7230L, tx.amountMinor)
        assertEquals("USD", tx.settlementCurrency)
        assertEquals(1000L, tx.settlementAmountMinor)
        assertFalse(tx.settlementIsEstimated)
    }

    @Test
    fun privateExportContainsNoFreeText() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        engine.ingestNotification(RawNotification("wallet", "钱包", "张某", "向张某付款5元，备注 private-fixture"))
        engine.flushExpired(true)
        store.addLog("CAPTURE", "", "旧样本：张某 private-fixture")
        val exported = engine.exportSanitizedDebugSamples()
        assertTrue(exported.contains("EXPENSE"))
        assertFalse(exported.contains("张某"))
        assertFalse(exported.contains("private-fixture"))
    }

    @Test
    fun bakedCredentialStaysAtConfiguredEndpoint() {
        val trusted = "https://api.deepseek.com"
        assertEquals("fixture", effectiveKey("", "$trusted/", trusted, "fixture"))
        assertEquals("", effectiveKey("", "https://example.org", trusted, "fixture"))
        assertEquals("override", effectiveKey("override", "https://example.org", trusted, "fixture"))
    }

    @Test
    fun rejectedHttpAndMalformedDirectionAreTerminal() {
        for (code in listOf(401, 402, 403, 429)) {
            assertThrows(IllegalStateException::class.java) { AiClient.checkHttpStatus(code) }
        }
        assertThrows(IllegalArgumentException::class.java) { AiClient.parseDirection("unexpected") }
    }

    @Test
    fun transportRetriesSurviveRestartAndStop() {
        val store = InMemoryLedgerStore()
        var calls = 0
        fun engine() = LedgerEngine(store, parse = { _, _, _ ->
            calls++
            throw java.io.IOException("fixture")
        })
        val first = engine()
        first.ingestNotification(RawNotification("bank", "银行", "付款", "付款成功12元"))
        first.flushExpired(true)
        repeat(6) { engine().replayOfflineQueue() }
        assertEquals(4, calls)
        assertTrue(store.listOfflineQueue().isEmpty())
        assertEquals(TxStatus.NEEDS_REVIEW, store.listTransactions().single().status)
    }

    @Test
    fun identicalCallbackAfterFlushIsIdempotent() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val event = RawNotification("bank", "银行", "消费", "消费12元")
        engine.ingestNotification(event)
        engine.flushExpired(true)
        engine.ingestNotification(event)
        assertTrue(engine.flushExpired(true).isEmpty())
    }

    // ==================== Step 2 New Golden Acceptance Tests ====================

    @Test
    fun originalPriceDiscountPaidAndBalanceRejectsAiSelectingBalance() {
        val store = InMemoryLedgerStore()
        val text = "原价 20 元，优惠 5 元，实付 15 元，余额 80 元"
        val extracted = AmountCandidateExtractor.extract(text)
        assertEquals(4, extracted.candidates.size)
        assertEquals(AmountRole.LIST_PRICE, extracted.candidates[0].role)
        assertEquals(AmountRole.DISCOUNT, extracted.candidates[1].role)
        assertEquals(AmountRole.PAID, extracted.candidates[2].role)
        assertEquals(AmountRole.BALANCE, extracted.candidates[3].role)
        assertEquals(1500L, extracted.primaryCandidate!!.amountMinor)

        // Even if a malicious/hallucinating AI returns the BALANCE candidate c4 (80.00), local validator MUST reject it and keep 15.00!
        val maliciousAiJson = JSONObject().apply {
            put("direction", "EXPENSE")
            put("selected_candidate_id", "c4")
            put("merchant", "测试餐饮店")
            put("item_summary", "午饭")
            put("category", "餐饮")
            put("payment_method", "微信零钱")
            put("needs_review", false)
            put("reasoning", "模型误选了余额")
        }
        val engine = LedgerEngine(
            store,
            parse = { _, profile, payload ->
                AiClient.validateAndApplyAiResponse(maliciousAiJson, profile, payload, "real_api:test")
            }
        )
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", text, t0))
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(1, summary.recordedCount)
        assertEquals(1500L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        val tx = store.listTransactions().single()
        assertEquals(1500L, tx.amountMinor)
        assertTrue(tx.aiReasoning.contains("BALANCE"))
        val links = store.listSourcesForTransaction(tx.id)
        assertEquals(1, links.size)
        assertEquals("c3", links.single().second.candidateId)
    }

    @Test
    fun withdrawalPrincipalAndFeeShareSingleSourceWhileArrivalDiscrepancyNeedsReview() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Case A: Explicit extra fee -> 1 internal transfer (1000.00) + 1 expense fee (1.00) linked to SAME source
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "零钱提现", "零钱提现 1,000 元，另收手续费 1 元", t0)
        )
        engine.flushExpired(true)

        val txsA = store.listTransactions()
        assertEquals(2, txsA.size)
        val principalTx = txsA.first { it.direction == TxDirection.INTERNAL_TRANSFER }
        val feeTx = txsA.first { it.direction == TxDirection.EXPENSE }
        assertEquals(100000L, principalTx.amountMinor)
        assertEquals(TxStatus.IGNORED, principalTx.status)
        assertEquals(100L, feeTx.amountMinor)
        assertEquals(TxStatus.RECORDED, feeTx.status)
        assertEquals("手续费", feeTx.category)

        val principalSources = store.listSourcesForTransaction(principalTx.id)
        val feeSources = store.listSourcesForTransaction(feeTx.id)
        assertEquals(1, principalSources.size)
        assertEquals(1, feeSources.size)
        assertEquals(principalSources.single().first.id, feeSources.single().first.id)
        assertEquals(SourceLinkRole.PRIMARY_PAYMENT, principalSources.single().second.linkRole)
        assertEquals(SourceLinkRole.FEE, feeSources.single().second.linkRole)
        assertEquals(2, store.listLinksForSource(principalSources.single().first.id).size)

        val summaryA = engine.getSummary(t0)
        assertEquals(100L, summaryA.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(1, summaryA.ignoredCount)

        // Case B: Arrival 999 vs withdrawal 1000 -> ambiguous fee deduction -> NEEDS_REVIEW (not silently ignored!)
        val storeB = InMemoryLedgerStore()
        val engineB = LedgerEngine(storeB)
        engineB.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "零钱提现", "零钱提现 1,000 元，到账 999 元", t0 + 5000L)
        )
        engineB.flushExpired(true)
        val txB = storeB.listTransactions().single()
        assertEquals(TxStatus.NEEDS_REVIEW, txB.status)
        assertTrue(txB.aiReasoning.contains("手续费"))
    }

    @Test
    fun foreignCurrencyEstimatedSettlementPreservesUsdBucketSeparately() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "信用卡消费", "尾号8841消费 USD 10，约合人民币 72.30 元", t0)
        )
        engine.flushExpired(true)

        val tx = store.listTransactions().single()
        assertEquals("USD", tx.currency)
        assertEquals(1000L, tx.amountMinor)
        assertEquals("CNY", tx.settlementCurrency)
        assertEquals(7230L, tx.settlementAmountMinor)
        assertTrue(tx.settlementIsEstimated)

        val summary = engine.getSummary(t0)
        assertEquals(0L, summary.byCurrency["CNY"]!!.grossExpenseMinor)
        assertEquals(1000L, summary.byCurrency["USD"]!!.grossExpenseMinor)
        assertEquals(1, summary.recordedCount)
    }

    @Test
    fun compositeSplitPaymentLinksBankAlertWithoutDoubleCounting() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Flush composite WeChat payment first (25.00 = 10.00 WeChat Balance + 15.00 Bank Card)
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "向盒马鲜生付款，微信余额 10 元＋银行卡 15 元，订单实付 25 元", t0)
        )
        engine.flushExpired(true)
        assertEquals(1, store.listTransactions().size)
        assertEquals(2500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // Later bank card debit of 15.00 arrives -> must link as SPLIT_FUNDING rather than double-counting +15.00!
        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡消费人民币15.00元，商户：财付通-盒马鲜生", t0 + 15_000L)
        )
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(1, store.listTransactions().size)
        assertEquals(2500L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        val tx = store.listTransactions().single()
        val links = store.listSourcesForTransaction(tx.id)
        assertEquals(2, links.size)
        assertTrue(links.any { it.second.linkRole == SourceLinkRole.SPLIT_FUNDING })
    }

    @Test
    fun installmentPurchaseAndRepaymentHandlePrincipalDueAndFeeSeparately() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Purchase confirmation: 1200 principal expense + 6 fee expense; 100 "首期应还" MUST NOT be booked
        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "分期提醒", "分期购机 1,200 元，首期应还 100 元，另收手续费 6 元", t0)
        )
        engine.flushExpired(true)

        assertEquals(2, store.listTransactions().size)
        assertEquals(120600L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // Subsequent repayment: 100 principal internal transfer (ignored) + 6 fee expense
        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "还款成功", "信用卡分期还款 100 元，另收手续费 6 元", t0 + 60_000L)
        )
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(3, summary.recordedCount) // 1200 + 6 + 6
        assertEquals(1, summary.ignoredCount)  // 100 principal repayment
        assertEquals(121200L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun aaAdvanceAndReimbursementOffsetPreserveCashflowSeparatelyFromRefund() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向海底捞AA 垫付 200 元", t0))
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信转账", "朋友返还 80 元", t0 + 15_000L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "入账通知", "公司报销上月差旅 500 元入账", t0 + 30_000L))
        engine.flushExpired(true)

        val txs = store.listTransactions()
        assertEquals(3, txs.size)
        assertEquals(0, txs.count { it.direction == TxDirection.REFUND })
        assertEquals(2, txs.count { it.direction == TxDirection.REIMBURSEMENT_OFFSET })

        val cny = engine.getSummary(t0).byCurrency["CNY"]!!
        assertEquals(20000L, cny.grossExpenseMinor)
        assertEquals(0L, cny.totalRefundMinor)
        assertEquals(58000L, cny.totalOffsetMinor)
        assertEquals(-38000L, cny.netPersonalExpenseMinor) // personal net burden reflects offsets
        assertEquals(58000L, cny.actualCashInflowMinor)    // actual cash inflow preserved separately
        assertEquals(0L, cny.totalIncomeMinor)
    }

    @Test
    fun refundPriorToOriginalExpenseBackLinksCleanly() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Refund arrives BEFORE original expense
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "退款通知", "美团外卖退款38.00元已退回", t0))
        engine.flushExpired(true)
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向美团外卖付款38.00元", t0 + 15_000L))
        engine.flushExpired(true)

        val refund = store.listTransactions().first { it.direction == TxDirection.REFUND }
        val expense = store.listTransactions().first { it.direction == TxDirection.EXPENSE }
        assertEquals(expense.id, refund.relatedTxId)
        assertEquals(0L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun mirrorNettingPairwiseUndoAndAmbiguousCandidatesAndStaleRevisionGuard() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // 1. Unique pair -> mirror netting + pairwise undo restores BOTH
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尾号6288网银转出5000.00元", t0))
        engine.flushExpired(true)
        engine.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账通知", "尾号3092跨行转入到账5000.00元", t0 + 20_000L))
        engine.flushExpired(true)
        assertEquals(2, engine.getSummary(t0).ignoredCount)
        assertEquals(0, engine.getSummary(t0).recordedCount)

        val mirrorOp = store.listChanges().first { it.opType == ChangeOpType.MARK_INTERNAL }
        assertTrue(engine.undoChange(mirrorOp.opId))
        assertEquals(0, engine.getSummary(t0).ignoredCount)
        assertEquals(2, engine.getSummary(t0).recordedCount)

        // 2. Stale undo guard: edit one tx, then old op undo MUST fail safely
        val tx = store.listTransactions().first()
        val markOp = engine.markAsInternal(tx.id, expectedRevision = tx.revision)!!
        assertNotNull(engine.rescueIgnored(tx.id, TxDirection.EXPENSE)) // advances revision
        assertFalse(engine.undoChange(markOp.opId)) // stale undo rejected!

        // 3. Ambiguous 2 mirror candidates -> NOT auto-ignored, flagged NEEDS_REVIEW!
        val store2 = InMemoryLedgerStore()
        val engine2 = LedgerEngine(store2)
        engine2.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尾号6288网银转出1000.00元", t0, notificationId = "m1"))
        engine2.flushExpired(true)
        engine2.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尾号8841网银转出1000.00元", t0 + 15_000L, notificationId = "m2"))
        engine2.flushExpired(true)
        engine2.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账通知", "尾号3092跨行转入到账1000.00元", t0 + 30_000L, notificationId = "m3"))
        engine2.flushExpired(true)
        assertEquals(0, engine2.getSummary(t0).ignoredCount)
        assertEquals(1, engine2.getSummary(t0).needsReviewCount)
    }

    @Test
    fun splitMergedTransactionUndoAndRefuseLegacyMissingSources() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向蜜雪冰城付款12.00元", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288消费12.00元，商户：财付通-蜜雪冰城", t0 + 500L))
        engine.flushExpired(true)

        val merged = store.listTransactions().single()
        assertEquals(2, store.listSourcesForTransaction(merged.id).size)

        val (splitTxs, splitChange) = engine.splitMergedTransaction(merged.id, merged.revision)!!
        assertEquals(2, splitTxs.size)
        assertEquals(2, engine.getSummary(t0).recordedCount)
        assertEquals(2400L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // Undo split restores exact 1 merged transaction and its 2 source links
        assertTrue(engine.undoChange(splitChange.opId))
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(1200L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(2, store.listSourcesForTransaction(merged.id).size)

        // Legacy record marked legacyMissingSources = true MUST refuse splitting
        val legacy = merged.copy(id = "legacy-tx", legacyMergedCount = 3, legacyMissingSources = true, revision = 1)
        store.saveTransaction(legacy)
        assertNull(engine.splitMergedTransaction(legacy.id))
    }

    @Test
    fun backupCodecRejectsWrongPasswordTamperingTruncationAndOversize() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款19.90元"))
        engine.flushExpired(true)

        val snapshot = store.exportLogicalSnapshot(1_700_000_000_000L)
        val encrypted = BackupCodec.encryptSnapshot(snapshot, "pass1234".toCharArray(), iterations = 10_000)

        val restored = BackupCodec.decryptSnapshot(encrypted, "pass1234".toCharArray())
        assertEquals(1, restored.transactions.size)
        assertEquals(1990L, restored.transactions.single().amountMinor)

        // Wrong password
        assertThrows(BackupException::class.java) {
            BackupCodec.decryptSnapshot(encrypted, "wrongpwd".toCharArray())
        }
        // Truncated container
        assertThrows(BackupException::class.java) {
            BackupCodec.decryptSnapshot(encrypted.copyOf(encrypted.size - 10), "pass1234".toCharArray())
        }
        // Tampered ciphertext / GCM auth tag
        val tamperedTag = encrypted.copyOf()
        tamperedTag[tamperedTag.lastIndex] = (tamperedTag.last().toInt() xor 0xFF).toByte()
        assertThrows(BackupException::class.java) {
            BackupCodec.decryptSnapshot(tamperedTag, "pass1234".toCharArray())
        }
        // Tampered clear header (AAD protection!)
        val tamperedHeader = encrypted.copyOf()
        tamperedHeader[12] = (tamperedHeader[12].toInt() xor 0x01).toByte()
        assertThrows(BackupException::class.java) {
            BackupCodec.decryptSnapshot(tamperedHeader, "pass1234".toCharArray())
        }
    }

    // ==================== Step 3: Real-Device Fixes & Read-Only Screen Capture ====================

    @Test
    fun realDevicePromoAdsBlockedWhileRealPaymentWithDiscountVoucherStillBooks() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Real-device observed marketing notifications that must be blocked in offline mode
        val ads = listOf(
            "微信支付" to "微信立减金申领指南：本月消费达标可领10元立减金",
            "招商银行" to "补贴申领提醒：您有50元消费立减金可预提取，点击查看活动规则",
            "支付宝" to "达标可领20元立减金，限今日预提取",
            "云闪付" to "消费补贴申领通道开启，最高立减30元，详见活动规则",
            "微信支付" to "您有一笔5元立减金待领取，快去使用吧"
        )
        ads.forEachIndexed { idx, (title, content) ->
            engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", title, content, t0 + idx * 1000L, notificationId = "ad-$idx")
            )
        }
        engine.flushExpired(true)
        assertEquals(0, store.listTransactions().size)

        // Real payment that ALSO mentions 立减金优惠 must still book the real paid amount!
        engine.ingestNotification(
            RawNotification(
                "com.tencent.mm",
                "微信",
                "微信支付",
                "微信支付凭证：向蜜雪冰城付款10.00元（已使用立减金优惠2.00元）",
                t0 + 20_000L,
                notificationId = "real-1"
            )
        )
        engine.flushExpired(true)

        val tx = store.listTransactions().single()
        assertEquals(TxDirection.EXPENSE, tx.direction)
        assertEquals(1000L, tx.amountMinor)
        assertEquals("蜜雪冰城", tx.merchant)
    }

    @Test
    fun summaryDistinguishesInternalTransfersFromOtherIgnoredAndWeChatDeepLinkAvoidsDeadUri() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：向瑞幸咖啡付款19.90元", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "银证转账", "尾号6288银行转证券向华泰证券转出5000.00元", t0 + 1000L))
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付分", "拼多多0元下单，已使用先用后付，确认收货后自动扣款39.90元", t0 + 2000L))
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(1, summary.recordedCount)
        assertEquals(2, summary.ignoredCount)
        assertEquals(1, summary.internalTransferCount)
        assertEquals(1, summary.otherIgnoredCount)
        assertEquals(1, summary.byCurrency["CNY"]!!.internalTransferCount)
        assertEquals(1, summary.byCurrency["CNY"]!!.otherIgnoredCount)

        val wxTx = store.listTransactions().first { it.direction == TxDirection.EXPENSE }
        assertFalse("Dead weixin://dl/wallet must never be generated", wxTx.deepLink.startsWith("weixin://"))
        assertEquals("walletwatcher://launch/com.tencent.mm", wxTx.deepLink)
    }

    @Test
    fun screenCaptureMergesWithInBufferUnknownMerchantWeChatNotification() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // 1. Real WeChat notification lacks merchant name ("已支付¥19.90")
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥19.90，付款方式：零钱", t0, notificationId = "wx-notif-1")
        )
        // 2. Read-only pay success screen arrives 2 seconds later with real merchant "7-11便利店(海岸城)"
        val capture = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
            rawTexts = listOf("支付成功", "7-11便利店(海岸城)", "¥", "19.90", "付款方式", "零钱", "完成"),
            observedAtMs = t0 + 2_000L
        )!!
        assertEquals(ScreenIngestOutcome.MERGED_INTO_BUFFER, engine.ingestScreenCapture(capture))
        engine.flushExpired(true)

        val txs = store.listTransactions()
        assertEquals(1, txs.size)
        val tx = txs.single()
        assertEquals(1990L, tx.amountMinor)
        assertEquals("7-11便利店(海岸城)", tx.merchant)
        assertEquals("餐饮", tx.category)
        assertEquals(2, tx.mergedNotifications)
        val sources = store.listSourcesForTransaction(tx.id)
        assertEquals(2, sources.size)
        assertTrue(sources.any { it.first.sourceType == "notification" })
        assertTrue(sources.any { it.first.sourceType == ScreenBillParser.SOURCE_TYPE_ACCESSIBILITY })
    }

    @Test
    fun screenCaptureBackfillsAlreadyCommittedUnknownMerchantAndSupportsUndoAndRelink() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 12:15:00", zone)!!

        // Notification arrives and flushes immediately as "未知商户" (日用)
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥28.00", t0, notificationId = "wx-old-1")
        )
        engine.flushExpired(true)
        val before = store.listTransactions().single()
        assertEquals("未知商户", before.merchant)
        assertEquals("日用", before.category)

        // Two hours later, user opens WeChat Bill Detail page -> atomic in-place merchant backfill!
        val detail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.mall.ui.MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "麦当劳餐厅", "-28.00",
                "当前状态", "支付成功",
                "商品", "板烧鸡腿堡套餐",
                "支付时间", "2026年11月26日 12:15:10",
                "支付方式", "零钱",
                "交易单号", "42000024998877665544"
            ),
            observedAtMs = t0 + 7_200_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, engine.ingestScreenCapture(detail))

        val after = store.listTransactions().single()
        assertEquals(before.id, after.id)
        assertEquals("麦当劳餐厅", after.merchant)
        assertEquals("餐饮", after.category)
        assertEquals(2, after.mergedNotifications)
        assertEquals(2, store.listSourcesForTransaction(after.id).size)

        // Re-opening the same bill detail is idempotently ignored via active externalId
        assertEquals(ScreenIngestOutcome.ALREADY_EXISTS, engine.ingestScreenCapture(detail))
        assertEquals(1, store.listTransactions().size)

        // Backfill change is cleanly undoable AND frees externalId for subsequent re-linking!
        val mergeOp = store.listChanges().first { it.opType == ChangeOpType.MERGE }
        assertTrue(engine.undoChange(mergeOp.opId))
        val undone = store.getTransaction(before.id)!!
        assertEquals("未知商户", undone.merchant)
        assertEquals(1, store.listSourcesForTransaction(undone.id).size)
        assertFalse(store.hasActiveSourceExternalId(detail.externalId!!))

        // Re-linking the undone bill detail succeeds again cleanly
        assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, engine.ingestScreenCapture(detail))
        assertEquals("麦当劳餐厅", store.getTransaction(before.id)!!.merchant)
    }

    @Test
    fun undoBackfillOnEmptyInitialLinksClearsAddedLinkCleanly() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 14:00:00", zone)!!

        // Legacy migrated tx with ZERO initial source links
        val legacyTx = Transaction(
            id = "legacy-zero-link",
            occurredAtMs = t0,
            observedAtMs = t0,
            updatedAtMs = t0,
            revision = 1,
            direction = TxDirection.EXPENSE,
            amountMinor = 2000L,
            merchant = "未知商户",
            itemSummary = "未知商户 交易",
            category = "日用",
            paymentMethod = "微信零钱",
            sourceChannels = listOf("微信"),
            mergedNotifications = 1,
            status = TxStatus.RECORDED,
            legacyMissingSources = true
        )
        store.saveTransaction(legacyTx)
        assertEquals(0, store.listSourcesForTransaction(legacyTx.id).size)

        val detail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "肯德基", "-20.00",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 14:00:15",
                "交易单号", "42000012345678901234"
            ),
            observedAtMs = t0 + 60_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, engine.ingestScreenCapture(detail))
        assertEquals(1, store.listSourcesForTransaction(legacyTx.id).size)

        val mergeOp = store.listChanges().first { it.opType == ChangeOpType.MERGE }
        assertTrue(engine.undoChange(mergeOp.opId))
        // Empty restoreLinks MUST clear the newly added link rather than leaving it behind!
        assertEquals(0, store.listSourcesForTransaction(legacyTx.id).size)
        assertFalse(store.hasActiveSourceExternalId(detail.externalId!!))
    }

    @Test
    fun billDetailWithoutTimeOrFromDifferentAppNeverBackfillsTodayTransaction() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 15:00:00", zone)!!

        // Today's ¥20.00 Alipay tx with unknown merchant
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "付款成功¥20.00", t0, notificationId = "ali-1")
        )
        engine.flushExpired(true)
        val aliTx = store.listTransactions().single()
        assertEquals("未知商户", aliTx.merchant)

        // 1. WeChat bill detail of ¥20.00 at the exact same minute MUST NOT cross-app match Alipay tx!
        val wxDetailSameTime = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "老乡鸡", "-20.00",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 15:00:10",
                "交易单号", "42000099990000111122"
            ),
            observedAtMs = t0 + 20_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine.ingestScreenCapture(wxDetailSameTime))
        assertEquals("未知商户", store.getTransaction(aliTx.id)!!.merchant)

        // 2. Alipay bill detail WITHOUT any page timestamp MUST NOT backfill today's tx by browse time!
        val aliDetailNoTime = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_ALIPAY,
            className = "BillDetailActivity",
            rawTexts = listOf(
                "账单详情", "上月某店", "-20.00",
                "交易状态", "交易成功",
                "付款方式", "花呗"
            ),
            observedAtMs = t0 + 10_000L,
            zoneId = zone
        )!!
        assertNull(aliDetailNoTime.eventTimeMs)
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine.ingestScreenCapture(aliDetailNoTime))
        assertEquals("未知商户", store.getTransaction(aliTx.id)!!.merchant)
        assertEquals(1, store.listTransactions().size)
    }

    @Test
    fun unnotifiedGroupChatReceiptBooksConsecutiveSameAmountAndHistoricalDetailDoesNotFabricate() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 13:05:10", zone)!!

        // 1. Two real group receipts of ¥3.30 arriving 5 seconds apart -> BOTH must be booked (total ¥6.60)
        val r1 = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            rawTexts = listOf("你已收款，资金已存入零钱", "¥3.30", "查看零钱", "转账时间", "2026-11-26 13:05:00", "收款时间", "2026-11-26 13:05:10"),
            observedAtMs = t0 + 500L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine.ingestScreenCapture(r1))
        engine.flushExpired(true)

        // 1b. Re-opening the EXACT SAME WeChat transfer bubble 30s later (new session) -> blocked by dual-second receiptDedupKey!
        assertEquals(
            ScreenIngestOutcome.ALREADY_EXISTS,
            engine.ingestScreenCapture(r1.copy(observedAtMs = t0 + 30_000L))
        )

        val r2 = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            rawTexts = listOf("你已收款，资金已存入零钱", "¥3.30", "查看零钱", "转账时间", "2026-11-26 13:05:15", "收款时间", "2026-11-26 13:05:20"),
            observedAtMs = t0 + 10_500L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine.ingestScreenCapture(r2))
        engine.flushExpired(true)

        assertEquals(2, store.listTransactions().size)
        assertEquals(660L, engine.getSummary(t0).byCurrency["CNY"]!!.totalIncomeMinor)

        // 1c. Opening an OLD (>5 min) WeChat transfer bubble never recorded before -> skipped as historical!
        val staleBubble = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            rawTexts = listOf("你已收款，资金已存入零钱", "¥9.90", "查看零钱", "收款时间", "2026-11-26 09:00:00"),
            observedAtMs = t0 + 60_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine.ingestScreenCapture(staleBubble))
        engine.flushExpired(true)
        assertEquals(2, store.listTransactions().size)

        // 2. Browsing an unrelated historical bill detail from last month MUST NOT create a fake row
        val oldBill = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "com.tencent.mm.plugin.mall.ui.MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "老乡鸡", "-24.50",
                "当前状态", "支付成功",
                "支付时间", "2026年10月12日 12:00:00",
                "交易单号", "42000011223344556677"
            ),
            observedAtMs = t0 + 60_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine.ingestScreenCapture(oldBill))
        engine.flushExpired(true)
        assertEquals(2, store.listTransactions().size)
    }

    @Test
    fun concurrentBillDetailRacingWithFlushAndDuplicateExternalIdProduceStrictlyOneTx() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 16:00:00", zone)!!

        // 1. Race BILL_DETAIL against flushExpired repeatedly with CountDownLatch
        repeat(15) { iter ->
            val store = InMemoryLedgerStore()
            val engine = LedgerEngine(store)
            engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥18.00", t0, notificationId = "n-$iter")
            )
            val detail = ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "MallTransactionDetailUI",
                rawTexts = listOf(
                    "返回", "瑞幸咖啡", "-18.00",
                    "当前状态", "支付成功",
                    "支付时间", "2026年11月26日 16:00:02",
                    "交易单号", "4200007777000000${1000 + iter}"
                ),
                observedAtMs = t0 + 3_000L,
                zoneId = zone
            )!!

            val ready = java.util.concurrent.CountDownLatch(2)
            val start = java.util.concurrent.CountDownLatch(1)
            val tFlush = Thread {
                ready.countDown(); start.await()
                engine.flushExpired(true)
            }
            val tDetail = Thread {
                ready.countDown(); start.await()
                engine.ingestScreenCapture(detail)
            }
            tFlush.start(); tDetail.start()
            ready.await(); start.countDown()
            tFlush.join(); tDetail.join()
            engine.flushExpired(true)

            assertEquals("Iter $iter must produce strictly 1 tx", 1, store.listTransactions().size)
            assertEquals("瑞幸咖啡", store.listTransactions().single().merchant)
        }

        // 2. Two concurrent threads submitting same live PAY_SUCCESS with identical real order ID -> strictly 1 tx
        val store2 = InMemoryLedgerStore()
        val engine2 = LedgerEngine(store2)
        val liveOrder = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf("支付成功", "星巴克", "¥35.00", "交易单号", "42000055556666777788"),
            observedAtMs = t0
        )!!
        val ready2 = java.util.concurrent.CountDownLatch(2)
        val start2 = java.util.concurrent.CountDownLatch(1)
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<ScreenIngestOutcome>()
        val w1 = Thread { ready2.countDown(); start2.await(); outcomes.add(engine2.ingestScreenCapture(liveOrder)) }
        val w2 = Thread { ready2.countDown(); start2.await(); outcomes.add(engine2.ingestScreenCapture(liveOrder)) }
        w1.start(); w2.start()
        ready2.await(); start2.countDown()
        w1.join(); w2.join()
        engine2.flushExpired(true)

        assertTrue(ScreenIngestOutcome.ALREADY_EXISTS in outcomes)
        assertEquals(1, store2.listTransactions().size)
    }

    @Test
    fun failedRestorePreservesDeferredNotificationAndSuccessfulRestoreReplaysScreenIdempotently() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 17:00:00", zone)!!

        // 1. Restore throws while notification arrives mid-barrier -> notification MUST still be replayed & booked!
        val store1 = InMemoryLedgerStore()
        val engine1 = LedgerEngine(store1)
        val midNotif = RawNotification("com.tencent.mm", "微信", "微信支付", "向蜜雪冰城付款12.00元", t0)
        assertThrows(RuntimeException::class.java) {
            engine1.withRestoreBarrier {
                engine1.ingestNotification(midNotif)
                throw RuntimeException("simulated restore failure")
            }
        }
        engine1.flushExpired(true)
        assertEquals(1, store1.listTransactions().size)
        assertEquals(1200L, store1.listTransactions().single().amountMinor)

        // 2. Successful restore contains order externalId; same screen capture arrives mid-restore -> replayed idempotently!
        val srcStore = InMemoryLedgerStore()
        val srcEngine = LedgerEngine(srcStore)
        srcEngine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥25.00", t0))
        srcEngine.flushExpired(true)
        val existingDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "瑞幸咖啡", "-25.00",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 17:00:05",
                "交易单号", "42000033334444555566"
            ),
            observedAtMs = t0 + 5_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, srcEngine.ingestScreenCapture(existingDetail))
        val snapshot = srcStore.exportLogicalSnapshot(t0 + 10_000L)

        val dstStore = InMemoryLedgerStore()
        val dstEngine = LedgerEngine(dstStore)
        dstEngine.withRestoreBarrier {
            dstStore.restoreFromSnapshot(snapshot)
            // Same order arrives mid-restore as a live screen capture -> deferred as Screen, NOT downgraded to RawNotification
            val sameOrderScreen = existingDetail.copy(pageKind = ScreenPageKind.PAY_SUCCESS)
            assertEquals(ScreenIngestOutcome.DEFERRED_RESTORE, dstEngine.ingestScreenCapture(sameOrderScreen))
        }
        dstEngine.flushExpired(true)
        assertEquals(1, dstStore.listTransactions().size)
        assertEquals("瑞幸咖啡", dstStore.listTransactions().single().merchant)
    }

    @Test
    fun localItemDescriptionImprovesOfflineSummaryAndCategoryWithoutEnteringCloudText() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        val capture = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf(
                "支付成功",
                "益康大门口",
                "¥45.00",
                "商品",
                "药店感冒药两盒",
                "完成"
            ),
            observedAtMs = t0
        )!!
        // Cloud-bound content excludes raw item description, while localItemHint carries it for offline use
        assertFalse(capture.toRawNotification().content.contains("感冒药"))
        assertEquals("药店感冒药两盒", capture.toRawNotification().localItemHint)

        assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine.ingestScreenCapture(capture))
        engine.flushExpired(true)

        val tx = store.listTransactions().single()
        assertEquals("药店感冒药两盒", tx.itemSummary)
        assertEquals("医疗健康", tx.category)
        assertFalse(tx.sanitizedText.contains("感冒药"))
    }

    @Test
    fun noTimeAlreadyProcessedAndAmbiguousBillDetailsNeverPolluteActiveBuffer() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 18:00:00", zone)!!

        // Case 1: Today's ¥3.30 bank notification is in buffer; old WeChat BILL_DETAIL WITHOUT timestamp arrives!
        val store1 = InMemoryLedgerStore()
        val engine1 = LedgerEngine(store1)
        engine1.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288消费人民币3.30元，商户：财付通", t0, notificationId = "bank-today-1")
        )
        val noTimeOldDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "账单详情", "历史旧店铺", "-3.30",
                "当前状态", "支付成功",
                "交易单号", "42000010101010101010"
            ),
            observedAtMs = t0 + 5_000L,
            zoneId = zone
        )!!
        assertNull(noTimeOldDetail.eventTimeMs)
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine1.ingestScreenCapture(noTimeOldDetail))
        engine1.flushExpired(true)
        val tx1 = store1.listTransactions().single()
        assertEquals("未知商户", tx1.merchant)
        assertEquals(1, tx1.mergedNotifications)
        assertEquals(1, store1.listSourcesForTransaction(tx1.id).size)

        // Case 2: Order #1 already committed in DB; today's new ¥3.30 notification is in buffer; re-opening Order #1
        //         MUST return ALREADY_EXISTS before touching today's buffer!
        val store2 = InMemoryLedgerStore()
        val engine2 = LedgerEngine(store2)
        engine2.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥3.30", t0, notificationId = "wx-1")
        )
        engine2.flushExpired(true)
        val order1Detail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "旧商户一", "-3.30",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 18:00:05",
                "交易单号", "42000020202020202020"
            ),
            observedAtMs = t0 + 10_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, engine2.ingestScreenCapture(order1Detail))

        // Second real ¥3.30 purchase enters active buffer 15s later
        engine2.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥3.30", t0 + 15_000L, notificationId = "wx-2")
        )
        // Re-opening Order #1 while Order #2 is in buffer -> MUST be rejected as ALREADY_EXISTS, NOT merged into Order #2!
        assertEquals(ScreenIngestOutcome.ALREADY_EXISTS, engine2.ingestScreenCapture(order1Detail.copy(observedAtMs = t0 + 16_000L)))
        engine2.flushExpired(true)

        val txs2 = store2.listTransactions().sortedBy { it.occurredAtMs }
        assertEquals(2, txs2.size)
        assertEquals("旧商户一", txs2[0].merchant)
        assertEquals("未知商户", txs2[1].merchant)
        assertEquals(1, txs2[1].mergedNotifications)
        assertEquals(1, store2.listSourcesForTransaction(txs2[1].id).size)

        // Case 3: Two compatible ¥3.30 buffers active simultaneously -> BILL_DETAIL skips as AMBIGUOUS!
        val store3 = InMemoryLedgerStore()
        val engine3 = LedgerEngine(store3)
        engine3.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥3.30", t0, notificationId = "wx-a")
        )
        engine3.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥3.30", t0 + 2_000L, notificationId = "wx-b")
        )
        val ambigDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "蜜雪冰城", "-3.30",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 18:00:01",
                "交易单号", "42000030303030303030"
            ),
            observedAtMs = t0 + 4_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.SKIPPED_AMBIGUOUS, engine3.ingestScreenCapture(ambigDetail))
        engine3.flushExpired(true)
        val txs3 = store3.listTransactions()
        assertEquals(2, txs3.size)
        assertTrue(txs3.all { it.merchant == "未知商户" && it.mergedNotifications == 1 })

        // Case 4: Buffer already holds a notification with strong externalId A -> BILL_DETAIL with DIFFERENT externalId B does NOT merge!
        val store4 = InMemoryLedgerStore()
        val engine4 = LedgerEngine(store4)
        engine4.ingestNotification(
            RawNotification(
                appPackage = ScreenBillParser.PKG_WECHAT,
                appName = "微信",
                title = "微信支付",
                content = "微信支付凭证：已支付¥3.30",
                timestampMs = t0,
                notificationId = "wx-order-a",
                externalId = "scr_existing_order_a"
            )
        )
        val detailB = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf(
                "返回", "瑞幸咖啡", "-3.30",
                "当前状态", "支付成功",
                "支付时间", "2026年11月26日 18:00:01",
                "交易单号", "42000040404040404042"
            ),
            observedAtMs = t0 + 2_000L,
            zoneId = zone
        )!!
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine4.ingestScreenCapture(detailB))
        engine4.flushExpired(true)
        val tx4 = store4.listTransactions().single()
        assertEquals("未知商户", tx4.merchant)
        assertEquals(1, tx4.mergedNotifications)
        // Case 5: Old BILL_DETAIL whose ONLY date sits inside "备注" ("预约时间 2026-11-26 10:30:00")
        //         MUST NOT use the remark date to pollute a same-time same-amount new transaction!
        val store5 = InMemoryLedgerStore()
        val engine5 = LedgerEngine(store5)
        val tAppt = ScreenBillParser.parseDateTimeMs("2026-11-26 10:30:00", zone)!!
        engine5.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥3.30", tAppt, notificationId = "wx-appt-today")
        )
        val remarkDateDetail = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "MallTransactionDetailUI",
            rawTexts = listOf("账单详情", "老店名称", "-3.30", "备注", "预约时间 2026-11-26 10:30:00"),
            observedAtMs = tAppt + 5_000L,
            zoneId = zone
        )!!
        assertNull(remarkDateDetail.eventTimeMs)
        assertEquals(ScreenIngestOutcome.SKIPPED_NO_MATCH, engine5.ingestScreenCapture(remarkDateDetail))
        engine5.flushExpired(true)
        val tx5 = store5.listTransactions().single()
        assertEquals("未知商户", tx5.merchant)
        assertEquals(1, tx5.mergedNotifications)
        assertEquals(1, store5.listSourcesForTransaction(tx5.id).size)
    }

    @Test
    fun privateItemSentinelNeverAppearsAnywhereInFinalCloudRequestJson() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L
        val sentinel = "PRIVATE_ITEM_SENTINEL_9981"

        // Case 1: Single-node item description right before amount with no merchant header
        val capture = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf(
                "支付成功",
                "商品说明",
                sentinel,
                "¥3.30",
                "完成"
            ),
            observedAtMs = t0
        )!!
        assertEquals("未知商户", capture.merchant)
        assertEquals(sentinel, capture.itemDescription)

        assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine.ingestScreenCapture(capture))

        // Inspect the EXACT serialized JSON request body that would be sent to an optional cloud LLM
        val queuedPayload = store.listOfflineQueue().single().payload
        val cloudBodyJson = AiClient.buildCloudRequestBody(
            AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, baseUrl = "https://api.example.com/v1", apiKey = "test", model = "test-model"),
            queuedPayload
        ).toString()
        assertFalse("Private item sentinel MUST NOT appear in any cloud request field", cloudBodyJson.contains(sentinel))
        assertFalse("Private item sentinel MUST NOT appear in sanitizedText", queuedPayload.sanitizedText.contains(sentinel))

        // Local offline flush still keeps local itemSummary cleanly
        engine.flushExpired(true)
        val tx = store.listTransactions().single()
        assertEquals(sentinel, tx.itemSummary)
        assertFalse(tx.sanitizedText.contains(sentinel))

        // Case 2: Multi-node split free text WITH standalone date node ("商品说明 / 定制礼盒 / 2026-11-26 10:30:00 / 送给小林 / ¥19.90")
        //         -> trailing unlabeled text MUST NEVER leak into merchant or cloud JSON!
        val store2 = InMemoryLedgerStore()
        val engine2 = LedgerEngine(store2)
        val multiCapture = ScreenBillParser.parse(
            packageName = ScreenBillParser.PKG_WECHAT,
            className = "WalletOrderInfoNewUI",
            rawTexts = listOf("支付成功", "商品说明", "定制礼盒", "2026-11-26 10:30:00", "送给小林", "¥19.90"),
            observedAtMs = t0 + 10_000L
        )!!
        assertEquals("未知商户", multiCapture.merchant)
        assertEquals("定制礼盒", multiCapture.itemDescription)
        assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine2.ingestScreenCapture(multiCapture))

        val multiPayload = store2.listOfflineQueue().single().payload
        val multiCloudJson = AiClient.buildCloudRequestBody(
            AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, baseUrl = "https://api.example.com/v1", apiKey = "test", model = "test-model"),
            multiPayload
        ).toString()
        assertFalse("Trailing free-text node '送给小林' MUST NOT appear anywhere in cloud JSON", multiCloudJson.contains("送给小林"))
        assertFalse("First free-text node '定制礼盒' MUST NOT appear anywhere in cloud JSON", multiCloudJson.contains("定制礼盒"))

        engine2.flushExpired(true)
        val tx2 = store2.listTransactions().single()
        assertEquals("未知商户", tx2.merchant)
        assertEquals("定制礼盒", tx2.itemSummary)
        assertFalse(tx2.sanitizedText.contains("送给小林"))
    }

    // ==================== Beta.4: Notification Whitelist Gate & Manual Ignore ====================

    @Test
    fun alipayJobPostingAndBrandNameNonTxNotificationsAreBlocked() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        val nonTxNotifications = listOf(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "向你发来一份岗位信息，薪资4500-4500元/月", t0, notificationId = "job-1"),
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝就业", "HR向你发来一份岗位信息：4500元", t0 + 100L, notificationId = "job-2"),
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "好友向你分享了一份好物清单，仅售4500元", t0 + 200L, notificationId = "brand-only-1"),
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "恭喜获得50000元可借额度，日利率低至0.02%", t0 + 300L, notificationId = "credit-offer-1"),
            RawNotification("com.tencent.mm", "微信", "微信支付", "本周账单报告：你共消费128.00元", t0 + 400L, notificationId = "report-1"),
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "你收到一份岗位信息，4500元，入职即享餐补实付报销", t0 + 500L, notificationId = "job-hard-1"),
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "消息通知 4500.00元", t0 + 600L, notificationId = "no-verb-1")
        )
        for (n in nonTxNotifications) {
            engine.ingestNotification(n)
        }
        engine.flushExpired(true)
        assertEquals(0, store.listTransactions().size)
        assertEquals(0, engine.getSummary(t0).recordedCount)
        assertEquals(0, engine.getSummary(t0).ignoredCount)

        // Genuine Alipay / credit-card transactions (including one mentioning 可用额度) MUST still book accurately
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "已支付¥12.50，商户：瑞幸咖啡", t0 + 2_000L, notificationId = "real-ali-1")
        )
        engine.ingestNotification(
            RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号8841信用卡消费100.00元，当前可用额度19900.00元", t0 + 3_000L, notificationId = "real-cc-1")
        )
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "你已收到¥36.00，来自王凯", t0 + 4_000L, notificationId = "real-ali-in-1")
        )
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(3, summary.recordedCount)
        assertEquals(11250L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(3600L, summary.byCurrency["CNY"]!!.totalIncomeMinor)
    }

    @Test
    fun salaryRateQuotesAndBrandNamesDoNotPromoteToPaidInAmountExtractor() {
        val jobExtract = AmountCandidateExtractor.extract("支付宝推荐岗位 4500元")
        assertNull(jobExtract.primaryCandidate)
        assertEquals(AmountRole.LIST_PRICE, jobExtract.candidates.single().role)

        val salaryExtract = AmountCandidateExtractor.extract("薪资4500元/月")
        assertNull(salaryExtract.primaryCandidate)
        assertEquals(AmountRole.LIST_PRICE, salaryExtract.candidates.single().role)

        val baseSalaryAdExtract = AmountCandidateExtractor.extract("招聘底薪8500元/月")
        assertNull(baseSalaryAdExtract.primaryCandidate)
        assertEquals(AmountRole.LIST_PRICE, baseSalaryAdExtract.candidates.single().role)

        val quoteExtract = AmountCandidateExtractor.extract("特价 19.90 元起，限时抢购")
        assertNull(quoteExtract.primaryCandidate)
        assertEquals(AmountRole.LIST_PRICE, quoteExtract.candidates.single().role)

        // "起息" must NOT be mistaken for standalone "元起" quote suffix
        val interestStartExtract = AmountCandidateExtractor.extract("定期转入 10000 元起息")
        assertEquals(AmountRole.PRINCIPAL, interestStartExtract.primaryCandidate!!.role)
        assertEquals(1000000L, interestStartExtract.primaryCandidate!!.amountMinor)

        // Completed salary deposits (including memo containing "底薪" or post-amount "已入账") must classify as PRINCIPAL
        val salaryDepositExtract = AmountCandidateExtractor.extract("您的账户代发薪资入账 8500.00 元")
        assertEquals(AmountRole.PRINCIPAL, salaryDepositExtract.primaryCandidate!!.role)
        assertEquals(850000L, salaryDepositExtract.primaryCandidate!!.amountMinor)

        val baseSalaryMemoExtract = AmountCandidateExtractor.extract("您尾号1234的账户已入账8500.00元，摘要：11月底薪")
        assertEquals(AmountRole.PRINCIPAL, baseSalaryMemoExtract.primaryCandidate!!.role)
        assertEquals(850000L, baseSalaryMemoExtract.primaryCandidate!!.amountMinor)

        val baseSalarySuffixVerbExtract = AmountCandidateExtractor.extract("您尾号1234的账户11月底薪8500.00元已入账")
        assertEquals(AmountRole.PRINCIPAL, baseSalarySuffixVerbExtract.primaryCandidate!!.role)
        assertEquals(850000L, baseSalarySuffixVerbExtract.primaryCandidate!!.amountMinor)

        // Completed payment to a merchant whose name contains "招聘" must still extract primary amount
        val recruitmentMerchantExtract = AmountCandidateExtractor.extract("付款成功，商户：某某招聘服务中心 100.00 元")
        assertEquals(10000L, recruitmentMerchantExtract.primaryCandidate!!.amountMinor)

        // Mixed completed charge + next-cycle estimate must strictly select completed amount regardless of clause order
        val mixedChargeFirst = AmountCandidateExtractor.extract("本次已扣15元，下期预计扣20元")
        assertEquals(AmountRole.PAID, mixedChargeFirst.primaryCandidate!!.role)
        assertEquals(1500L, mixedChargeFirst.primaryCandidate!!.amountMinor)
        assertEquals(AmountRole.INSTALLMENT_DUE, mixedChargeFirst.candidates.first { it.amountMinor == 2000L }.role)

        val mixedEstimateFirst = AmountCandidateExtractor.extract("下期预计扣20元，本次已扣15元")
        assertEquals(AmountRole.PAID, mixedEstimateFirst.primaryCandidate!!.role)
        assertEquals(1500L, mixedEstimateFirst.primaryCandidate!!.amountMinor)
        assertEquals(AmountRole.INSTALLMENT_DUE, mixedEstimateFirst.candidates.first { it.amountMinor == 2000L }.role)

        // Upcoming fee in next-cycle clause MUST be INSTALLMENT_DUE (never FEE), while actual fee stays FEE
        val upcomingFeeExtract = AmountCandidateExtractor.extract("本次已扣款15元，下期预计扣手续费2元")
        assertEquals(AmountRole.PAID, upcomingFeeExtract.primaryCandidate!!.role)
        assertEquals(1500L, upcomingFeeExtract.primaryCandidate!!.amountMinor)
        assertNull(upcomingFeeExtract.feeCandidate)
        assertEquals(AmountRole.INSTALLMENT_DUE, upcomingFeeExtract.candidates.first { it.amountMinor == 200L }.role)

        val actualFeeExtract = AmountCandidateExtractor.extract("本次已扣款15元，另收手续费2元")
        assertEquals(AmountRole.PAID, actualFeeExtract.primaryCandidate!!.role)
        assertEquals(1500L, actualFeeExtract.primaryCandidate!!.amountMinor)
        assertEquals(AmountRole.FEE, actualFeeExtract.feeCandidate!!.role)
        assertEquals(200L, actualFeeExtract.feeCandidate!!.amountMinor)

        val brandOnly = AmountCandidateExtractor.extract("支付宝：向你分享 4500元")
        assertEquals(AmountRole.UNKNOWN, brandOnly.candidates.single().role)

        val realAliPaid = AmountCandidateExtractor.extract("支付宝：已支付¥19.90")
        assertEquals(AmountRole.PAID, realAliPaid.primaryCandidate!!.role)
        assertEquals(1990L, realAliPaid.primaryCandidate!!.amountMinor)

        val realSubCharge = AmountCandidateExtractor.extract("会员自动续费扣款15.00元/月")
        assertEquals(AmountRole.PAID, realSubCharge.primaryCandidate!!.role)
        assertEquals(1500L, realSubCharge.primaryCandidate!!.amountMinor)
    }

    @Test
    fun salaryDepositAndRecruitmentMerchantBookWhileJobAdsAreBlocked() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Job ads (including 底薪/月薪/薪资) must NOT book
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "招聘月薪 8500 元", t0, notificationId = "job-ad-1"))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "招聘：月薪 4500 元，入职享餐补实付报销", t0 + 100L, notificationId = "job-ad-2"))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "HR招聘，薪资 8500 元", t0 + 200L, notificationId = "job-ad-3"))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "招聘底薪8500元/月", t0 + 300L, notificationId = "job-ad-4"))
        engine.flushExpired(true)
        assertEquals(0, store.listTransactions().size)

        // Real bank salary deposits containing "薪资" or memo "底薪" MUST book as INCOME
        engine.ingestNotification(
            RawNotification("com.android.mms", "短信", "银行通知", "您的账户代发薪资入账 8500.00 元", t0 + 1_000L, "sms", notificationId = "salary-in-1")
        )
        engine.ingestNotification(
            RawNotification("com.android.mms", "短信", "银行通知", "您尾号1234的账户已入账8500.00元，摘要：11月底薪", t0 + 1_500L, "sms", notificationId = "salary-in-2")
        )
        // Real payment whose merchant name contains "招聘" MUST book as EXPENSE
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "付款成功，商户：某某招聘服务中心 100.00 元", t0 + 2_000L, notificationId = "rec-pay-1")
        )
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(3, summary.recordedCount)
        assertEquals(1700000L, summary.byCurrency["CNY"]!!.totalIncomeMinor)
        assertEquals(10000L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        val txs = store.listTransactions()
        val incomes = txs.filter { it.direction == TxDirection.INCOME }
        assertEquals(2, incomes.size)
        assertTrue(incomes.all { it.category == "工资收入" && it.status == TxStatus.RECORDED })
        val expense = txs.first { it.direction == TxDirection.EXPENSE }
        assertEquals("某某招聘服务中心", expense.merchant)
    }

    @Test
    fun mixedCompletedChargeAndNextCycleReminderBooksOnlyActualCharge() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Pure comma-separated pending / upcoming reminders without any completed clause must stay blocked
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "待付款提醒，向星巴克付款 19.90 元", t0, notificationId = "comma-pending-1")
        )
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "续费提醒，将于明日自动续费扣款 15.00元/月", t0 + 100L, notificationId = "comma-upcoming-1")
        )
        engine.flushExpired(true)
        assertEquals(0, store.listTransactions().size)

        // 1. Same notification has completed debit + next-month auto-renew notice -> books 15.00
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "本次已扣款15.00元，下月将自动续费", t0 + 1_000L, notificationId = "mixed-sub-1")
        )
        engine.flushExpired(true)
        assertEquals(1, store.listTransactions().size)
        assertEquals(1500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // 2. Same notification has completed 15元 + next-cycle estimated 20元 -> MUST book strictly 15元 (NOT 20元)
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "本次已扣15元，下期预计扣20元", t0 + 30_000L, notificationId = "mixed-sub-2")
        )
        engine.flushExpired(true)
        assertEquals(2, store.listTransactions().size)
        assertEquals(3000L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // 3. Reversed clause order ("下期预计扣20元，本次已扣15元") -> still books strictly 15元
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "下期预计扣20元，本次已扣15元", t0 + 60_000L, notificationId = "mixed-sub-3")
        )
        engine.flushExpired(true)
        val txs = store.listTransactions()
        assertEquals(3, txs.size)
        assertTrue(txs.all { it.direction == TxDirection.EXPENSE && it.status == TxStatus.RECORDED && it.amountMinor == 1500L })
        assertEquals(4500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun upcomingFeeInMixedNotificationIsNotBookedWhileActualFeeStillBooks() {
        val t0 = 1_700_000_000_000L

        // 1. Upcoming fee in next-cycle reminder clause MUST NOT spawn a premature fee expense
        val storeUpcoming = InMemoryLedgerStore()
        val engineUpcoming = LedgerEngine(storeUpcoming)
        engineUpcoming.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "本次已扣款15元，下期预计扣手续费2元", t0, notificationId = "upcoming-fee-1")
        )
        engineUpcoming.flushExpired(true)
        assertEquals(1, storeUpcoming.listTransactions().size)
        val onlyTx = storeUpcoming.listTransactions().single()
        assertEquals(TxDirection.EXPENSE, onlyTx.direction)
        assertEquals(TxStatus.RECORDED, onlyTx.status)
        assertEquals(1500L, onlyTx.amountMinor)
        assertEquals(1500L, engineUpcoming.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // 2. Variant future fee wording ("下月将收取手续费2元") also stays non-bookable
        val storeUpcoming2 = InMemoryLedgerStore()
        val engineUpcoming2 = LedgerEngine(storeUpcoming2)
        engineUpcoming2.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "本次已扣款15元，下月将收取手续费2元", t0 + 1_000L, notificationId = "upcoming-fee-2")
        )
        engineUpcoming2.flushExpired(true)
        assertEquals(1, storeUpcoming2.listTransactions().size)
        assertEquals(1500L, engineUpcoming2.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // 3. Control case: genuine occurred fee ("本次已扣款15元，另收手续费2元") MUST still book 1500 + 200 = 1700
        val storeActual = InMemoryLedgerStore()
        val engineActual = LedgerEngine(storeActual)
        engineActual.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "本次已扣款15元，另收手续费2元", t0 + 2_000L, notificationId = "actual-fee-1")
        )
        engineActual.flushExpired(true)
        val actualTxs = storeActual.listTransactions()
        assertEquals(2, actualTxs.size)
        assertTrue(actualTxs.any { it.amountMinor == 1500L && it.direction == TxDirection.EXPENSE })
        assertTrue(actualTxs.any { it.amountMinor == 200L && it.direction == TxDirection.EXPENSE && it.category == "手续费" })
        assertEquals(1700L, engineActual.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
    }

    @Test
    fun mixedZeroAuthWithUpcomingChargeProducesTraceableIgnoredRecordAndZeroExpense() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // Mixed 0-yuan pay-later authorization + tomorrow's upcoming charge -> traceable IGNORED record, 0 main expense
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付分", "已使用先用后付，0元下单，明日将自动扣款19.90元", t0, notificationId = "zero-auth-mixed-1")
        )
        engine.flushExpired(true)

        val summary1 = engine.getSummary(t0)
        assertEquals(0, summary1.recordedCount)
        assertEquals(0L, summary1.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(1, summary1.ignoredCount)
        assertEquals(1, summary1.otherIgnoredCount)
        assertEquals(0, summary1.internalTransferCount)

        val authTx = store.listTransactions().single()
        assertEquals(TxDirection.IGNORED, authTx.direction)
        assertEquals(TxStatus.IGNORED, authTx.status)
        assertEquals("忽略提醒", authTx.category)
        val authSources = store.listSourcesForTransaction(authTx.id)
        assertEquals(1, authSources.size)
        assertEquals(AmountRole.INSTALLMENT_DUE, authSources.single().first.amountCandidates.single().role)

        // When the actual charge happens the next day, it books cleanly as 19.90 EXPENSE while keeping the prior ignored auth
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "先用后付自动扣款19.90元成功", t0 + 86_400_000L, notificationId = "zero-auth-paid-1")
        )
        engine.flushExpired(true)

        val summary2 = engine.getSummary(t0)
        assertEquals(1, summary2.recordedCount)
        assertEquals(1990L, summary2.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(1, summary2.otherIgnoredCount)
    }

    @Test
    fun pendingPaymentAndUpcomingAutoDebitRemindersAreBlockedWhileActualChargeBooksOnce() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        // 1. Pending payment reminder + subsequent actual payment (same merchant & amount) -> strictly 1 expense!
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "待付款提醒：向星巴克付款 19.90 元", t0, notificationId = "pending-1")
        )
        engine.flushExpired(true)
        assertEquals(0, store.listTransactions().size)

        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "付款成功：向星巴克付款 19.90 元", t0 + 20_000L, notificationId = "paid-1")
        )
        engine.flushExpired(true)
        assertEquals(1, store.listTransactions().size)
        assertEquals(1990L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // 2. Promo monthly price + upcoming auto-debit reminders MUST be blocked, while actual recurring charge books cleanly
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "腾讯视频会员特惠 15.00元/月，限时开通", t0 + 30_000L, notificationId = "rate-promo-1")
        )
        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "支付宝", "明日将自动扣款 19.90 元", t0 + 31_000L, notificationId = "upcoming-1")
        )
        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "续费提醒：将于明日自动续费扣款 15.00元/月", t0 + 32_000L, notificationId = "upcoming-2")
        )
        engine.flushExpired(true)
        assertEquals(1, store.listTransactions().size)

        engine.ingestNotification(
            RawNotification("com.tencent.mm", "微信", "微信支付", "会员自动续费扣款15.00元/月", t0 + 40_000L, notificationId = "sub-paid-1")
        )
        engine.flushExpired(true)

        val summary = engine.getSummary(t0)
        assertEquals(2, summary.recordedCount)
        assertEquals(3490L, summary.byCurrency["CNY"]!!.netPersonalExpenseMinor) // 19.90 + 15.00 only
    }

    @Test
    fun merchantExtractionIgnoresPronounDirectionAndGenericXiangPhrases() {
        assertEquals("未知商户", AiClient.extractMerchant("支付宝：向你发来一份岗位信息 4500元"))
        assertEquals("未知商户", AiClient.extractMerchant("向朋友分享好物 4500元"))
        assertEquals("未知商户", AiClient.extractMerchant("王凯通过扫码向你付款50.00元"))
        assertEquals("未知商户", AiClient.extractMerchant("向您转账100.00元"))
        assertEquals("你好便利店", AiClient.extractMerchant("向你好便利店付款19.90元"))
        assertEquals("您好大药房", AiClient.extractMerchant("向您好大药房付款28.00元"))
        assertEquals("瑞幸咖啡", AiClient.extractMerchant("向瑞幸咖啡付款19.90元"))
        assertEquals("中国移动", AiClient.extractMerchant("向中国移动话费充值100.00元"))
        assertEquals("海底捞", AiClient.extractMerchant("向海底捞AA 垫付 200 元"))
        assertEquals("张记面馆", AiClient.extractMerchant("付款给张记面馆"))
    }

    @Test
    fun markAsIgnoredRemovesFromLedgerSupportsUndoAndGuardedByRevision() {
        val store = InMemoryLedgerStore()
        val engine = LedgerEngine(store)
        val t0 = 1_700_000_000_000L

        engine.ingestNotification(
            RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向星巴克付款45.00元", t0, notificationId = "n-ign-1")
        )
        engine.flushExpired(true)
        val tx = store.listTransactions().single()
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(4500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

        // Stale revision guard
        assertNull(engine.markAsIgnored(tx.id, expectedRevision = tx.revision + 99))

        val ch = engine.markAsIgnored(tx.id, expectedRevision = tx.revision)
        assertNotNull(ch)
        val ignoredTx = store.getTransaction(tx.id)!!
        assertEquals(TxDirection.IGNORED, ignoredTx.direction)
        assertEquals(TxStatus.IGNORED, ignoredTx.status)
        assertEquals("忽略提醒", ignoredTx.category)

        val summaryAfterIgnore = engine.getSummary(t0)
        assertEquals(0, summaryAfterIgnore.recordedCount)
        assertEquals(0L, summaryAfterIgnore.byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(1, summaryAfterIgnore.otherIgnoredCount)
        assertEquals(0, summaryAfterIgnore.internalTransferCount)

        // Idempotent second call on already-ignored tx returns null
        assertNull(engine.markAsIgnored(tx.id, expectedRevision = ignoredTx.revision))

        // Undo cleanly restores original expense
        assertTrue(engine.undoChange(ch!!.opId))
        val restored = store.getTransaction(tx.id)!!
        assertEquals(TxDirection.EXPENSE, restored.direction)
        assertEquals(TxStatus.RECORDED, restored.status)
        assertEquals(4500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
        assertEquals(0, engine.getSummary(t0).otherIgnoredCount)
    }
}
