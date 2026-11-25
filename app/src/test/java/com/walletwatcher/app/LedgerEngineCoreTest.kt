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
        private var aiConfig: AiConfig = AiConfig()
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
        engine.updateAiConfig(AiConfig())
        val replayed = engine.replayOfflineQueue()
        assertEquals(1, replayed)
        assertEquals(0, engine.getSummary().offlineQueueCount)
        assertEquals(4.00, engine.getSummary().netExpense, 0.001)
    }
}
