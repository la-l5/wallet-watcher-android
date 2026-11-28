package com.walletwatcher.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.walletwatcher.app.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LedgerDatabaseRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun freshDbName(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().take(8)}.db"

    private class LegacyV2Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 2) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE transactions (
                    id TEXT PRIMARY KEY,
                    created_at_ms INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    amount REAL NOT NULL,
                    merchant TEXT NOT NULL,
                    item_summary TEXT NOT NULL,
                    category TEXT NOT NULL,
                    payment_method TEXT NOT NULL,
                    source_channels TEXT NOT NULL,
                    merged_notifications INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    ignore_reason TEXT NOT NULL DEFAULT '',
                    deep_link TEXT NOT NULL DEFAULT '',
                    deep_link_label TEXT NOT NULL DEFAULT '',
                    sanitized_text TEXT NOT NULL DEFAULT '',
                    ai_mode_used TEXT NOT NULL DEFAULT 'mock',
                    ai_reasoning TEXT NOT NULL DEFAULT '',
                    related_tx_id TEXT,
                    rescued_by_user INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at_ms DESC)")
            db.execSQL("CREATE TABLE offline_queue (queue_id TEXT PRIMARY KEY, enqueued_at_ms INTEGER NOT NULL, payload_json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at_ms INTEGER NOT NULL, stage TEXT NOT NULL, message TEXT NOT NULL, sanitized_sample TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value_json TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun v2UpgradeWithWalDataConvertsDoubleToMinorAndMarksLegacyMissingSources() {
        val dbName = freshDbName("v2_upgrade")
        val legacy = LegacyV2Helper(context, dbName)
        val rawDb = legacy.writableDatabase
        rawDb.beginTransaction()
        try {
            fun insertOld(id: String, amt: Double, merged: Int) {
                val cv = ContentValues().apply {
                    put("id", id)
                    put("created_at_ms", 1_700_000_000_000L)
                    put("direction", "EXPENSE")
                    put("amount", amt)
                    put("merchant", "蜜雪冰城")
                    put("item_summary", "柠檬水")
                    put("category", "餐饮")
                    put("payment_method", "招商银行(6288)")
                    put("source_channels", "[\"微信\",\"招商银行\"]")
                    put("merged_notifications", merged)
                    put("status", "RECORDED")
                }
                rawDb.insert("transactions", null, cv)
            }
            insertOld("old-1", 19.95, 3)
            insertOld("old-bad", -5.0, 1)
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }
        // Leave WAL active and reopen via v3 LedgerDatabase
        legacy.close()

        val upgraded = LedgerDatabase(context, dbName)
        try {
            val txs = upgraded.listTransactions()
            assertEquals(2, txs.size)

            val normal = upgraded.getTransaction("old-1")!!
            assertEquals(1995L, normal.amountMinor)
            assertEquals("CNY", normal.currency)
            assertEquals(2, normal.scale)
            assertTrue(normal.legacyMissingSources)
            assertEquals(3, normal.legacyMergedCount)
            assertTrue(upgraded.listSourcesForTransaction("old-1").isEmpty())

            val bad = upgraded.getTransaction("old-bad")!!
            assertEquals(0L, bad.amountMinor)
            assertEquals(TxStatus.NEEDS_REVIEW, bad.status)
            assertTrue(bad.legacyMissingSources)

            val report = upgraded.lastMigrationReport
            assertNotNull(report)
            assertEquals(2, report!!.migratedRows)
            assertEquals(1, report.anomalousRows)
            assertEquals(2, report.legacyMissingSourceRows)

            // Engine refuses to split legacy row with missing sources
            val engine = LedgerEngine(upgraded)
            assertNull(engine.splitMergedTransaction("old-1"))
        } finally {
            upgraded.close()
        }

        // Re-opening upgraded DB must remain idempotent with exact same 2 rows
        val reopened = LedgerDatabase(context, dbName)
        try {
            assertEquals(2, reopened.listTransactions().size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun encryptedLogicalSnapshotExportAndAtomicRestoreInWalExcludesApiKey() {
        val srcDb = LedgerDatabase(context, freshDbName("backup_src"))
        val dstDb = LedgerDatabase(context, freshDbName("backup_dst"))
        try {
            val srcEngine = LedgerEngine(srcDb)
            srcEngine.updateProfile(UserProfile(realName = "林晓明", ownCardTails = listOf("6288", "3092")))
            srcEngine.updateAiConfig(
                AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, apiKey = "secret-test-key-must-never-export")
            )
            // Switch back to offline parse so tests run deterministically with zero network
            srcEngine.updateAiConfig(
                newConfig = AiConfig(mode = AiConfig.MODE_LOCAL),
                persistConfig = AiConfig(mode = AiConfig.MODE_OPENAI_COMPATIBLE, apiKey = "secret-test-key-must-never-export")
            )

            val t0 = 1_700_000_000_000L
            srcEngine.ingestNotification(RawNotification("com.tencent.mm", "微信", "零钱提现", "零钱提现 1,000 元，另收手续费 1 元", t0))
            srcEngine.ingestNotification(RawNotification("cmb.pb", "招商银行", "信用卡消费", "尾号8841消费 USD 10，约合人民币 72.30 元", t0 + 1000L))
            srcEngine.flushExpired(true)

            val srcBackup = BackupManager(srcEngine)
            val encrypted = srcBackup.exportEncryptedBackup("strong-pass".toCharArray(), iterations = 10_000, nowMs = t0 + 5000L)

            // Decrypt and confirm API key is completely absent from serialized snapshot
            val decrypted = BackupCodec.decryptSnapshot(encrypted, "strong-pass".toCharArray())
            val serialized = BackupCodec.serializeSnapshot(decrypted).toString()
            assertFalse(serialized.contains("secret-test-key-must-never-export"))
            assertEquals(3, decrypted.transactions.size) // 1000 internal + 1 fee + 10 USD
            assertEquals(2, decrypted.sources.size)
            assertEquals(3, decrypted.sourceLinks.size)

            // Restore into fresh destination SQLite DB
            val dstEngine = LedgerEngine(dstDb)
            val report = BackupManager(dstEngine).restoreEncryptedBackup(encrypted, "strong-pass".toCharArray())
            assertEquals(3, report.transactionCount)
            assertEquals(2, report.sourceCount)
            assertEquals(3, report.linkCount)
            assertEquals("林晓明", dstEngine.profile.realName)
            assertEquals(AiConfig.MODE_LOCAL, dstDb.loadAiConfig().mode)
            assertEquals("", dstDb.loadAiConfig().apiKey)

            val dstSummary = dstEngine.getSummary(t0)
            assertEquals(100L, dstSummary.byCurrency["CNY"]!!.netPersonalExpenseMinor)
            assertEquals(1000L, dstSummary.byCurrency["USD"]!!.grossExpenseMinor)
            assertEquals(1, dstSummary.ignoredCount)
        } finally {
            srcDb.close()
            dstDb.close()
        }
    }

    @Test
    fun failedOrInterruptedRestoreRollsBackAndKeepsOriginalLedgerIntact() {
        val liveDb = LedgerDatabase(context, freshDbName("rollback_live"))
        val otherDb = LedgerDatabase(context, freshDbName("rollback_other"))
        try {
            val t0 = 1_700_000_000_000L
            val liveEngine = LedgerEngine(liveDb)
            liveEngine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向瑞幸咖啡付款19.90元", t0))
            liveEngine.flushExpired(true)
            assertEquals(1990L, liveEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

            val otherEngine = LedgerEngine(otherDb)
            otherEngine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向星巴克付款35.00元", t0))
            otherEngine.flushExpired(true)
            val validContainer = BackupManager(otherEngine).exportEncryptedBackup("pwd1234".toCharArray(), iterations = 10_000)

            val liveBackup = BackupManager(liveEngine)

            // 1. Wrong password -> original intact
            assertThrows(BackupException::class.java) {
                liveBackup.restoreEncryptedBackup(validContainer, "wrong!".toCharArray())
            }
            assertEquals(1990L, liveEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

            // 2. Truncated file -> original intact
            assertThrows(BackupException::class.java) {
                liveBackup.restoreEncryptedBackup(validContainer.copyOf(24), "pwd1234".toCharArray())
            }
            assertEquals(1990L, liveEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

            // 3. Simulated crash BEFORE atomic swap -> original intact
            assertThrows(BackupException::class.java) {
                liveBackup.restoreEncryptedBackup(validContainer, "pwd1234".toCharArray(), beforeSwapHook = {
                    throw RuntimeException("simulated crash before swap")
                })
            }
            assertEquals(1990L, liveEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
            assertEquals("瑞幸咖啡", liveDb.listTransactions().single().merchant)

            // 4. Simulated crash DURING swap -> rollback restores original DB cleanly!
            assertThrows(BackupException::class.java) {
                liveBackup.restoreEncryptedBackup(validContainer, "pwd1234".toCharArray(), duringSwapHook = {
                    throw RuntimeException("simulated IO error during swap")
                })
            }
            assertEquals(1990L, liveEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
            assertEquals("瑞幸咖啡", liveDb.listTransactions().single().merchant)
        } finally {
            liveDb.close()
            otherDb.close()
        }
    }

    @Test
    fun inWindowPendingBackupFinalizesExactlyOnceAndMidRestoreEventsArePreserved() {
        val srcDb = LedgerDatabase(context, freshDbName("pending_src"))
        val dstDb = LedgerDatabase(context, freshDbName("pending_dst"))
        try {
            val t0 = 1_700_000_000_000L
            val srcEngine = LedgerEngine(srcDb)
            // Ingest inside dedup window WITHOUT flushing yet (snapshot is in offline_queue)
            srcEngine.ingestNotification(RawNotification("com.tencent.mm", "微信", "微信支付", "向蜜雪冰城付款12.00元", t0))
            assertEquals(1, srcEngine.activeBufferCount())
            assertEquals(0, srcDb.listTransactions().size)

            val encrypted = BackupManager(srcEngine).exportEncryptedBackup("pass9999".toCharArray(), iterations = 10_000)

            val dstEngine = LedgerEngine(dstDb)
            // Also inject a brand-new notification WHILE restore barrier is active
            val midRestoreEvent = RawNotification("com.eg.android.AlipayGphone", "支付宝", "付款成功", "向盒马鲜生付款30.00元", t0 + 60_000L)
            BackupManager(dstEngine).restoreEncryptedBackup(
                encrypted,
                "pass9999".toCharArray(),
                beforeSwapHook = {
                    dstEngine.ingestNotification(midRestoreEvent)
                }
            )

            // Replay restored pending item twice -> must commit the ¥12.00 tx EXACTLY ONCE!
            assertEquals(1, dstEngine.replayOfflineQueue())
            assertEquals(0, dstEngine.replayOfflineQueue())
            // Flush the notification that arrived during restore
            dstEngine.flushExpired(forceAll = true)

            val allTxs = dstDb.listTransactions()
            assertEquals(2, allTxs.size)
            assertEquals(4200L, dstEngine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
            assertEquals(1, allTxs.count { it.merchant == "蜜雪冰城" && it.amountMinor == 1200L })
            assertEquals(1, allTxs.count { it.merchant == "盒马鲜生" && it.amountMinor == 3000L })
        } finally {
            srcDb.close()
            dstDb.close()
        }
    }

    @Test
    fun sqlitePersistsAccessibilityBackfillAndExternalIdIdempotency() {
        val db = LedgerDatabase(context, freshDbName("screen_backfill"))
        try {
            val engine = LedgerEngine(db)
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val t0 = ScreenBillParser.parseDateTimeMs("2026-11-26 18:20:00", zone)!!

            engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：已支付¥15.00", t0, notificationId = "n-1")
            )
            engine.flushExpired(true)
            val original = db.listTransactions().single()
            assertEquals("未知商户", original.merchant)

            val detail = ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "com.tencent.mm.plugin.mall.ui.MallTransactionDetailUI",
                rawTexts = listOf(
                    "返回", "全家便利店", "-15.00",
                    "当前状态", "支付成功",
                    "支付时间", "2026-11-26 18:20:05",
                    "交易单号", "42000088776655443322"
                ),
                observedAtMs = t0 + 180_000L,
                zoneId = zone
            )!!
            assertEquals(ScreenIngestOutcome.BACKFILLED_COMMITTED, engine.ingestScreenCapture(detail))
            assertTrue(db.hasActiveSourceExternalId(detail.externalId!!))
            assertEquals(ScreenIngestOutcome.ALREADY_EXISTS, engine.ingestScreenCapture(detail))

            val updated = db.getTransaction(original.id)!!
            assertEquals("全家便利店", updated.merchant)
            assertEquals("餐饮", updated.category)
            assertEquals(2, db.listSourcesForTransaction(updated.id).size)

            // Undoing backfill removes link and frees active externalId in real SQLite!
            val mergeOp = db.listChanges().first { it.opType == ChangeOpType.MERGE }
            assertTrue(engine.undoChange(mergeOp.opId))
            assertFalse(db.hasActiveSourceExternalId(detail.externalId!!))
            assertEquals(1, db.listSourcesForTransaction(original.id).size)

            // Updating an existing transaction in-place (e.g. markAsInternal) MUST preserve its source links!
            val afterUndo = db.getTransaction(original.id)!!
            assertNotNull(engine.markAsInternal(afterUndo.id, expectedRevision = afterUndo.revision))
            assertEquals(1, db.listSourcesForTransaction(original.id).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun sqliteFindBackfillCandidatesQueriesBeyond200RowsAndExcludesReverted() {
        val db = LedgerDatabase(context, freshDbName("backfill_query"))
        try {
            val tOld = 1_700_000_000_000L
            db.saveTransaction(
                Transaction(
                    id = "old-target",
                    occurredAtMs = tOld,
                    direction = TxDirection.EXPENSE,
                    amountMinor = 330L,
                    merchant = "未知商户",
                    itemSummary = "未知商户 交易",
                    category = "日用",
                    paymentMethod = "微信零钱",
                    sourceChannels = listOf("微信"),
                    mergedNotifications = 1,
                    status = TxStatus.RECORDED
                )
            )
            db.saveTransaction(
                Transaction(
                    id = "old-reverted",
                    occurredAtMs = tOld + 1000L,
                    direction = TxDirection.EXPENSE,
                    amountMinor = 330L,
                    merchant = "未知商户",
                    itemSummary = "已撤销",
                    category = "日用",
                    paymentMethod = "微信零钱",
                    sourceChannels = listOf("微信"),
                    mergedNotifications = 1,
                    status = TxStatus.REVERTED
                )
            )
            // Insert 205 newer transactions to exceed any 200-row scan limit
            for (i in 1..205) {
                db.saveTransaction(
                    Transaction(
                        id = "newer-$i",
                        occurredAtMs = tOld + 86_400_000L + i * 1000L,
                        direction = TxDirection.EXPENSE,
                        amountMinor = 1000L + i,
                        merchant = "商户$i",
                        itemSummary = "消费",
                        category = "日用",
                        paymentMethod = "微信零钱",
                        sourceChannels = listOf("微信"),
                        mergedNotifications = 1,
                        status = TxStatus.RECORDED
                    )
                )
            }
            val matches = db.findBackfillCandidates(330L, "CNY", tOld - 60_000L, tOld + 60_000L)
            assertEquals(1, matches.size)
            assertEquals("old-target", matches.single().id)

            // Verify hasActiveSourceFingerprint works against real SQLite WAL
            val engine = LedgerEngine(db)
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val rTime = ScreenBillParser.parseDateTimeMs("2026-11-26 13:05:10", zone)!!
            val receipt = ScreenBillParser.parse(
                packageName = ScreenBillParser.PKG_WECHAT,
                className = "RemittanceDetailUI",
                rawTexts = listOf("你已收款，资金已存入零钱", "¥3.30", "转账时间", "2026-11-26 13:05:00", "收款时间", "2026-11-26 13:05:10"),
                observedAtMs = rTime + 1000L,
                zoneId = zone
            )!!
            assertEquals(ScreenIngestOutcome.ENQUEUED_NEW, engine.ingestScreenCapture(receipt))
            engine.flushExpired(true)
            assertTrue(db.hasActiveSourceFingerprint(receipt.receiptDedupKey!!))
            assertEquals(ScreenIngestOutcome.ALREADY_EXISTS, engine.ingestScreenCapture(receipt.copy(observedAtMs = rTime + 20_000L)))
        } finally {
            db.close()
        }
    }

    @Test
    fun sqliteMarkAsIgnoredPreservesSourceLinksAndUndoRestoresExpense() {
        val db = LedgerDatabase(context, freshDbName("mark_ignored"))
        try {
            val engine = LedgerEngine(db)
            val t0 = 1_700_000_000_000L
            engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：向瑞幸咖啡付款15.00元", t0, notificationId = "wx-ign-1")
            )
            engine.flushExpired(true)

            val tx = db.listTransactions().single()
            assertEquals(1, db.listSourcesForTransaction(tx.id).size)
            assertEquals(1500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)

            val ch = engine.markAsIgnored(tx.id, expectedRevision = tx.revision)!!
            val updated = db.getTransaction(tx.id)!!
            assertEquals(TxDirection.IGNORED, updated.direction)
            assertEquals(TxStatus.IGNORED, updated.status)
            assertEquals(1, db.listSourcesForTransaction(tx.id).size)
            val sumAfter = engine.getSummary(t0)
            assertEquals(0L, sumAfter.byCurrency["CNY"]!!.netPersonalExpenseMinor)
            assertEquals(1, sumAfter.otherIgnoredCount)
            assertEquals(0, sumAfter.internalTransferCount)

            assertTrue(engine.undoChange(ch.opId))
            val restored = db.getTransaction(tx.id)!!
            assertEquals(TxDirection.EXPENSE, restored.direction)
            assertEquals(TxStatus.RECORDED, restored.status)
            assertEquals(1, db.listSourcesForTransaction(tx.id).size)
            assertEquals(1500L, engine.getSummary(t0).byCurrency["CNY"]!!.netPersonalExpenseMinor)
        } finally {
            db.close()
        }
    }
}
