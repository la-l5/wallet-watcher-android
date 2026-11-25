package com.walletwatcher.app

import com.walletwatcher.app.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LedgerEngineCoreTest {

    private class InMemoryLedgerStore(
        private var profile: UserProfile = UserProfile(
            realName = "林晓明",
            aliases = listOf("小明"),
            ownCardTails = listOf("6288", "8841", "3092")
        ),
        private var aiConfig: AiConfig = AiConfig(mode = AiConfig.MODE_MOCK)
    ) : LedgerStore {
        private val txs = LinkedHashMap<String, Transaction>()
        private val queue = LinkedHashMap<String, OfflineQueueItem>()
        private val logs = mutableListOf<AuditLogEntry>()

        override fun saveTransaction(tx: Transaction) { txs[tx.id] = tx }
        override fun getTransaction(txId: String): Transaction? = txs[txId]
        override fun listTransactions(limit: Int): List<Transaction> =
            txs.values.sortedByDescending { it.createdAtMs }.take(limit)

        override fun findRecentMirrorCandidate(amount: Double, oppositeDirection: TxDirection, sinceMs: Long): Transaction? =
            txs.values
                .filter { it.status == TxStatus.RECORDED && it.direction == oppositeDirection && abs(it.amount - amount) < 0.005 && it.createdAtMs >= sinceMs }
                .maxByOrNull { it.createdAtMs }

        override fun findRecentExpenseForRefund(amount: Double, merchant: String, sinceMs: Long): Transaction? =
            txs.values
                .filter { it.status == TxStatus.RECORDED && it.direction == TxDirection.EXPENSE && it.amount >= amount - 0.005 && it.createdAtMs >= sinceMs }
                .maxByOrNull { it.createdAtMs }

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
        override fun clearLedgerAndLogs() { txs.clear(); queue.clear(); logs.clear() }
    }

    @Test
    fun coffeeExpenseAndSalaryWithSanitization() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款19.90元，商品：生椰拿铁"))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尊敬的林晓明，您尾号6288储蓄卡代发工资入账12800.00元，手机13812345678，卡号6225880112346288"))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(19.90, summary.netExpense, 0.001)
        assertEquals(12800.00, summary.totalIncome, 0.001)

        val salary = engine.store.listTransactions().first { it.direction == TxDirection.INCOME }
        assertFalse(salary.sanitizedText.contains("13812345678"))
        assertFalse(salary.sanitizedText.contains("6225880112346288"))
    }

    @Test
    fun tripleChannelDedupMergesIntoOneTransaction() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = System.currentTimeMillis()
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向蜜雪冰城付款12.00元，商品：柠檬水", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288储蓄卡消费12.00元，财付通-蜜雪冰城", t0 + 800L))
        engine.ingestNotification(RawNotification("com.unionpay", "云闪付", "交易通知", "卡号6225880112346288消费12.00元", t0 + 1500L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(1, summary.recordedCount)
        assertEquals(12.00, summary.netExpense, 0.001)
        val tx = engine.store.listTransactions().first()
        assertEquals(3, tx.mergedNotifications)
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
        assertEquals(199.00, summary.netExpense, 0.001)
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

        // Anonymous cross-bank self transfer -> mirror netting
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账通知", "尾号6288网银转出5000.00元", t0 + 20_000L))
        engine.flushExpired(forceAll = true)
        engine.ingestNotification(RawNotification("com.ccb.android", "建设银行", "动账通知", "尾号3092跨行转入到账5000.00元", t0 + 45_000L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary()
        assertEquals(0, summary.recordedCount)
        assertEquals(0.0, summary.netExpense, 0.001)
        assertEquals(0.0, summary.totalIncome, 0.001)
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

        assertEquals(0.0, engine.getSummary().netExpense, 0.001)
        assertEquals(0.0, engine.getSummary().totalIncome, 0.001)

        // Offline garage test
        engine.updateAiConfig(AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, apiKey = "fixture"))
        online = false
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "深圳地铁乘车码免密扣款4.00元", t0 + 50_000L))
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary().offlineQueueCount)

        online = true
        engine.updateAiConfig(AiConfig(mode = AiConfig.MODE_MOCK))
        val replayed = engine.replayOfflineQueue()
        assertEquals(1, replayed)
        assertEquals(0, engine.getSummary().offlineQueueCount)
        assertEquals(4.00, engine.getSummary().netExpense, 0.001)
    }

    @Test
    fun sameAmountDifferentMerchantsAndRefundAcrossMonths() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val time = 1700000000000L
        engine.ingestNotification(RawNotification("a", "微信", "付款", "向甲商店付款20元", time))
        engine.ingestNotification(RawNotification("b", "支付宝", "付款", "向乙商店付款20元", time + 500))
        engine.flushExpired(true)
        assertEquals(40.0, engine.getSummary(time).netExpense, 0.001)
        val later = time + 32L * 86400000
        engine.ingestNotification(RawNotification("a", "微信", "退款", "甲商店退款20元", later))
        engine.flushExpired(true)
        assertEquals(-20.0, engine.getSummary(later).netExpense, 0.001)
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
        assertEquals(12.00, engine.getSummary(t0).netExpense, 0.001)
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
        assertEquals(59.00, engine.getSummary(t0).netExpense, 0.001)
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
        assertEquals(28.00, engine.getSummary(t0).netExpense, 0.001)
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
        assertEquals(85.50, engine.getSummary(t0).netExpense, 0.001)
    }

    @Test
    fun refundToBankCardMergesIntoSingleRefundAndDoesNotInflateIncome() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向美团外卖付款38.00元", t0))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "尾号6288消费38.00元，商户：财付通-美团", t0 + 500L))
        engine.flushExpired(forceAll = true)
        assertEquals(1, engine.getSummary(t0).recordedCount)
        assertEquals(38.00, engine.getSummary(t0).netExpense, 0.001)

        // Refund arrives via WeChat (REFUND) and CMB bank card credit (IN) within seconds
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "退款通知", "美团外卖退款38.00元已退回招商银行尾号6288", t0 + 30_000L))
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡入账人民币38.00元，对方：财付通-美团", t0 + 31_000L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary(t0)
        assertEquals(2, summary.recordedCount) // 1 expense + 1 merged refund
        assertEquals(0.00, summary.netExpense, 0.001)
        assertEquals(0.00, summary.totalIncome, 0.001)
    }

    @Test
    fun outOfOrderMirrorTransferStillNetsOut() {
        val engine = LedgerEngine(InMemoryLedgerStore())
        val t0 = 1_700_000_000_000L
        // Receiving bank notifies first (t0 + 2s), sending bank SMS arrives 2s later with timestamp t0
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
        // 收款通知包含“付款方”或“向你付款”绝不能被误判为支出
        engine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：收款200.00元，付款方：王凯", t0))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "收款提醒", "王凯通过扫码向你付款+50.00元，已存入余额", t0 + 100L))
        // 带负号的支出通知
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡支出 -35.50元，商户：星巴克", t0 + 200L))
        // 话费充值属于真实消费而非内部流转
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向中国移动话费充值100.00元", t0 + 300L))
        // 扣款失败 / 余额不足必须彻底忽略
        engine.ingestNotification(RawNotification("cmb.pb", "招商银行", "交易提醒", "您尾号6288储蓄卡交易失败（余额不足），交易金额399.00元", t0 + 400L))
        engine.ingestNotification(RawNotification("com.eg.android.AlipayGphone", "支付宝", "扣款通知", "自动扣款失败，未能扣除腾讯视频会员15.00元", t0 + 500L))
        engine.flushExpired(forceAll = true)

        val summary = engine.getSummary(t0)
        assertEquals(4, summary.recordedCount)
        assertEquals(250.00, summary.totalIncome, 0.001)
        assertEquals(135.50, summary.netExpense, 0.001)
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
        assertEquals(72.30, store.listTransactions().single().amount, 0.001)
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
        assertEquals("fixture", effectiveKey("", trusted + "/", trusted, "fixture"))
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
}
