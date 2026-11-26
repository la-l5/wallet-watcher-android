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
        private val txs = LinkedHashMap<String, Transaction>()
        private val sources = LinkedHashMap<String, TransactionSource>()
        private val links = mutableListOf<TransactionSourceLink>()
        private val changes = LinkedHashMap<String, TransactionChange>()
        private val rules = LinkedHashMap<String, UserMerchantRule>()
        private val queue = LinkedHashMap<String, OfflineQueueItem>()
        private val logs = mutableListOf<AuditLogEntry>()

        override fun saveTransaction(tx: Transaction) { txs[tx.id] = tx }

        override fun saveTransactionBundle(
            transactions: List<Transaction>,
            sources: List<TransactionSource>,
            links: List<TransactionSourceLink>,
            change: TransactionChange?
        ) {
            for (s in sources) this.sources[s.id] = s
            for (t in transactions) this.txs[t.id] = t
            for (l in links) {
                this.links.removeAll { it.transactionId == l.transactionId && it.sourceId == l.sourceId && it.linkRole == l.linkRole }
                this.links.add(l)
            }
            if (change != null) changes[change.opId] = change
        }

        override fun getTransaction(txId: String): Transaction? = txs[txId]

        override fun listTransactions(limit: Int): List<Transaction> =
            txs.values.sortedByDescending { it.occurredAtMs }.take(limit)

        override fun listSourcesForTransaction(txId: String): List<Pair<TransactionSource, TransactionSourceLink>> =
            links.filter { it.transactionId == txId }.mapNotNull { l ->
                sources[l.sourceId]?.let { it to l }
            }

        override fun listLinksForSource(sourceId: String): List<TransactionSourceLink> =
            links.filter { it.sourceId == sourceId }

        override fun listAllSources(): List<TransactionSource> = sources.values.toList()
        override fun listAllLinks(): List<TransactionSourceLink> = links.toList()
        override fun listChanges(limit: Int): List<TransactionChange> = changes.values.reversed().take(limit)
        override fun getChange(opId: String): TransactionChange? = changes[opId]
        override fun saveChange(change: TransactionChange) { changes[change.opId] = change }
        override fun deleteLinksForTransaction(txId: String) { links.removeAll { it.transactionId == txId } }
        override fun saveLinks(links: List<TransactionSourceLink>) {
            for (l in links) {
                this.links.removeAll { it.transactionId == l.transactionId && it.sourceId == l.sourceId && it.linkRole == l.linkRole }
                this.links.add(l)
            }
        }

        override fun listUserRules(): List<UserMerchantRule> = rules.values.toList()
        override fun saveUserRule(rule: UserMerchantRule) { rules[rule.id] = rule }
        override fun deleteUserRule(ruleId: String) { rules.remove(ruleId) }

        override fun findRecentMirrorCandidates(
            amountMinor: Long,
            currency: String,
            oppositeDirection: TxDirection,
            sinceMs: Long,
            untilMs: Long
        ): List<Transaction> =
            txs.values.filter {
                it.status == TxStatus.RECORDED &&
                    it.direction == oppositeDirection &&
                    it.currency == currency &&
                    it.amountMinor == amountMinor &&
                    it.occurredAtMs in sinceMs..untilMs
            }

        override fun findRecentExpenseForRefund(
            amountMinor: Long,
            currency: String,
            merchant: String,
            sinceMs: Long
        ): Transaction? =
            txs.values
                .filter {
                    it.status == TxStatus.RECORDED &&
                        it.direction == TxDirection.EXPENSE &&
                        it.currency == currency &&
                        it.amountMinor >= amountMinor &&
                        it.occurredAtMs >= sinceMs
                }
                .maxByOrNull { it.occurredAtMs }

        override fun findRecentSplitFundedCandidate(
            partAmountMinor: Long,
            currency: String,
            sinceMs: Long,
            untilMs: Long
        ): Pair<Transaction, TransactionSource>? {
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
                    if (found != null) return tx to src
                }
            }
            return null
        }

        override fun enqueueOffline(item: OfflineQueueItem) { queue[item.queueId] = item }
        override fun listOfflineQueue(): List<OfflineQueueItem> = queue.values.sortedBy { it.enqueuedAtMs }
        override fun removeOfflineItem(queueId: String) { queue.remove(queueId) }
        override fun addLog(stage: String, message: String, sanitizedSample: String) {
            logs.add(AuditLogEntry(logs.size.toLong() + 1L, System.currentTimeMillis(), stage, message, sanitizedSample))
        }
        override fun listLogs(limit: Int): List<AuditLogEntry> = logs.reversed().take(limit)
        override fun loadProfile(): UserProfile = profile
        override fun saveProfile(profile: UserProfile) { this.profile = profile }
        override fun loadAiConfig(): AiConfig = aiConfig
        override fun saveAiConfig(config: AiConfig) { this.aiConfig = config }
        override fun getDbSizeBytes(): Long = 4096L
        override fun clearLedgerAndLogs() {
            txs.clear(); sources.clear(); links.clear(); changes.clear(); queue.clear(); logs.clear()
        }

        override fun <T> runInTransaction(block: () -> T): T = block()

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
}
