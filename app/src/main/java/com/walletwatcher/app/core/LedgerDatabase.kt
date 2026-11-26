package com.walletwatcher.app.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.walletwatcher.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Storage contract so LedgerEngine can be unit-tested cleanly on pure JVM and Robolectric Android SQLite.
 */
interface LedgerStore {
    fun saveTransaction(tx: Transaction)
    fun saveTransactionBundle(
        transactions: List<Transaction>,
        sources: List<TransactionSource>,
        links: List<TransactionSourceLink>,
        change: TransactionChange? = null
    )
    fun getTransaction(txId: String): Transaction?
    fun listTransactions(limit: Int = 300): List<Transaction>
    fun listSourcesForTransaction(txId: String): List<Pair<TransactionSource, TransactionSourceLink>>
    fun listLinksForSource(sourceId: String): List<TransactionSourceLink>
    fun hasActiveSourceExternalId(externalId: String): Boolean
    fun hasActiveSourceFingerprint(shortFingerprint: String): Boolean
    fun findBackfillCandidates(
        amountMinor: Long,
        currency: String,
        sinceMs: Long,
        untilMs: Long
    ): List<Transaction>
    fun listAllSources(): List<TransactionSource>
    fun listAllLinks(): List<TransactionSourceLink>
    fun listChanges(limit: Int = 200): List<TransactionChange>
    fun getChange(opId: String): TransactionChange?
    fun saveChange(change: TransactionChange)
    fun deleteLinksForTransaction(txId: String)
    fun saveLinks(links: List<TransactionSourceLink>)
    fun listUserRules(): List<UserMerchantRule>
    fun saveUserRule(rule: UserMerchantRule)
    fun deleteUserRule(ruleId: String)

    fun findRecentMirrorCandidates(
        amountMinor: Long,
        currency: String,
        oppositeDirection: TxDirection,
        sinceMs: Long,
        untilMs: Long
    ): List<Transaction>

    fun findRecentExpenseForRefund(
        amountMinor: Long,
        currency: String,
        merchant: String,
        sinceMs: Long
    ): Transaction?

    fun findRecentSplitFundedCandidate(
        partAmountMinor: Long,
        currency: String,
        sinceMs: Long,
        untilMs: Long
    ): Pair<Transaction, TransactionSource>?

    fun enqueueOffline(item: OfflineQueueItem)
    fun listOfflineQueue(): List<OfflineQueueItem>
    fun removeOfflineItem(queueId: String)
    fun addLog(stage: String, message: String, sanitizedSample: String = "")
    fun listLogs(limit: Int = 120): List<AuditLogEntry>
    fun loadProfile(): UserProfile
    fun saveProfile(profile: UserProfile)
    fun loadAiConfig(): AiConfig
    fun saveAiConfig(config: AiConfig)
    fun getDbSizeBytes(): Long
    fun clearLedgerAndLogs()

    fun <T> runInTransaction(block: () -> T): T
    fun exportLogicalSnapshot(nowMs: Long = System.currentTimeMillis()): BackupSnapshot
    fun restoreFromSnapshot(
        snapshot: BackupSnapshot,
        beforeSwapHook: (() -> Unit)? = null,
        duringSwapHook: (() -> Unit)? = null
    ): RestoreReport
}

class LedgerDatabase(
    context: Context,
    private val dbName: String = DB_NAME
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION), LedgerStore {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    var lastMigrationReport: MigrationReport? = null
        private set

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createV3Schema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at_ms DESC)")
        }
        if (oldVersion < 3) {
            migrateV2ToV3(db, oldVersion)
        }
    }

    private fun createV3Schema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transactions (
                id TEXT PRIMARY KEY,
                occurred_at_ms INTEGER NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 1,
                tx_type TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(amount_minor >= 0),
                currency TEXT NOT NULL DEFAULT 'CNY',
                scale INTEGER NOT NULL DEFAULT 2,
                settlement_amount_minor INTEGER,
                settlement_currency TEXT,
                settlement_scale INTEGER NOT NULL DEFAULT 2,
                settlement_is_estimated INTEGER NOT NULL DEFAULT 0,
                merchant TEXT NOT NULL,
                item_summary TEXT NOT NULL,
                category TEXT NOT NULL,
                payment_method TEXT NOT NULL,
                source_channels TEXT NOT NULL,
                merged_notifications INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL,
                ignore_reason TEXT NOT NULL DEFAULT '',
                deep_link TEXT NOT NULL DEFAULT '',
                deep_link_label TEXT NOT NULL DEFAULT '',
                sanitized_text TEXT NOT NULL DEFAULT '',
                ai_mode_used TEXT NOT NULL DEFAULT 'local',
                ai_reasoning TEXT NOT NULL DEFAULT '',
                related_tx_id TEXT,
                rescued_by_user INTEGER NOT NULL DEFAULT 0,
                legacy_merged_count INTEGER NOT NULL DEFAULT 0,
                legacy_missing_sources INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_occurred ON transactions(occurred_at_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_status_type ON transactions(status, tx_type, currency, amount_minor)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_sources (
                id TEXT PRIMARY KEY,
                source_type TEXT NOT NULL,
                app_package TEXT NOT NULL,
                app_name TEXT NOT NULL,
                external_id TEXT,
                observed_at_ms INTEGER NOT NULL,
                source_event_time_ms INTEGER,
                amount_candidates_json TEXT NOT NULL DEFAULT '[]',
                matcher_version TEXT NOT NULL DEFAULT 'v3-local-1',
                short_fingerprint TEXT NOT NULL DEFAULT '',
                card_tail TEXT,
                merchant_hint TEXT NOT NULL DEFAULT '',
                channel_label TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_src_observed ON transaction_sources(observed_at_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_source_links (
                transaction_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                link_role TEXT NOT NULL,
                candidate_id TEXT,
                PRIMARY KEY (transaction_id, source_id, link_role),
                FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
                FOREIGN KEY (source_id) REFERENCES transaction_sources(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_link_source ON transaction_source_links(source_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_changes (
                op_id TEXT PRIMARY KEY,
                op_type TEXT NOT NULL,
                affected_json TEXT NOT NULL,
                before_revisions_json TEXT NOT NULL,
                after_revisions_json TEXT NOT NULL,
                inverse_payload_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_changes_created ON transaction_changes(created_at_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_rules (
                id TEXT PRIMARY KEY,
                source_scope TEXT NOT NULL DEFAULT '*',
                normalized_merchant TEXT NOT NULL,
                category TEXT NOT NULL,
                rule_version INTEGER NOT NULL DEFAULT 1,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_queue (
                queue_id TEXT PRIMARY KEY,
                enqueued_at_ms INTEGER NOT NULL,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at_ms INTEGER NOT NULL,
                stage TEXT NOT NULL,
                message TEXT NOT NULL,
                sanitized_sample TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateV2ToV3(db: SQLiteDatabase, oldVersion: Int) {
        val hasLegacyTable = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='transactions'",
            null
        ).use { it.moveToFirst() }

        val hasOccurredColumn = if (hasLegacyTable) {
            db.rawQuery("PRAGMA table_info(transactions)", null).use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(1) == "occurred_at_ms") found = true
                }
                found
            }
        } else {
            false
        }

        if (!hasLegacyTable || hasOccurredColumn) {
            createV3Schema(db)
            return
        }

        db.execSQL("ALTER TABLE transactions RENAME TO transactions_v2_legacy")
        db.execSQL("DROP INDEX IF EXISTS idx_tx_created")
        createV3Schema(db)

        var migratedRows = 0
        var anomalousRows = 0
        var legacyMissingSourceRows = 0
        val notes = mutableListOf<String>()

        db.rawQuery("SELECT * FROM transactions_v2_legacy", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms"))
                val direction = c.getString(c.getColumnIndexOrThrow("direction"))
                val rawAmount = c.getDouble(c.getColumnIndexOrThrow("amount"))
                val minor = MoneyMath.doubleToMinor(rawAmount, 2)
                var status = c.getString(c.getColumnIndexOrThrow("status"))
                var reasoning = c.getString(c.getColumnIndexOrThrow("ai_reasoning"))
                val safeMinor = if (minor != null) {
                    minor
                } else {
                    anomalousRows++
                    status = TxStatus.NEEDS_REVIEW.name
                    reasoning = "[v2→v3 迁移异常金额已置 0 待核对: $rawAmount] $reasoning"
                    notes.add("tx=$id 原始金额异常($rawAmount)")
                    0L
                }

                val mergedCount = c.getInt(c.getColumnIndexOrThrow("merged_notifications")).coerceAtLeast(1)
                legacyMissingSourceRows++

                val cv = ContentValues().apply {
                    put("id", id)
                    put("occurred_at_ms", createdAtMs)
                    put("observed_at_ms", createdAtMs)
                    put("updated_at_ms", createdAtMs)
                    put("revision", 1)
                    put("tx_type", direction)
                    put("amount_minor", safeMinor)
                    put("currency", "CNY")
                    put("scale", 2)
                    putNull("settlement_amount_minor")
                    putNull("settlement_currency")
                    put("settlement_scale", 2)
                    put("settlement_is_estimated", 0)
                    put("merchant", c.getString(c.getColumnIndexOrThrow("merchant")))
                    put("item_summary", c.getString(c.getColumnIndexOrThrow("item_summary")))
                    put("category", c.getString(c.getColumnIndexOrThrow("category")))
                    put("payment_method", c.getString(c.getColumnIndexOrThrow("payment_method")))
                    put("source_channels", c.getString(c.getColumnIndexOrThrow("source_channels")))
                    put("merged_notifications", mergedCount)
                    put("status", status)
                    put("ignore_reason", c.getString(c.getColumnIndexOrThrow("ignore_reason")))
                    put("deep_link", c.getString(c.getColumnIndexOrThrow("deep_link")))
                    put("deep_link_label", c.getString(c.getColumnIndexOrThrow("deep_link_label")))
                    put("sanitized_text", c.getString(c.getColumnIndexOrThrow("sanitized_text")))
                    put("ai_mode_used", c.getString(c.getColumnIndexOrThrow("ai_mode_used")))
                    put("ai_reasoning", reasoning)
                    val related = c.getString(c.getColumnIndexOrThrow("related_tx_id"))
                    if (related != null) put("related_tx_id", related) else putNull("related_tx_id")
                    put("rescued_by_user", c.getInt(c.getColumnIndexOrThrow("rescued_by_user")))
                    put("legacy_merged_count", mergedCount)
                    put("legacy_missing_sources", 1)
                }
                db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                migratedRows++
            }
        }

        db.execSQL("DROP TABLE IF EXISTS transactions_v2_legacy")

        val report = MigrationReport(
            fromVersion = oldVersion,
            toVersion = DB_VERSION,
            migratedRows = migratedRows,
            anomalousRows = anomalousRows,
            legacyMissingSourceRows = legacyMissingSourceRows,
            notes = notes
        )
        lastMigrationReport = report

        val reportJson = JSONObject().apply {
            put("fromVersion", report.fromVersion)
            put("toVersion", report.toVersion)
            put("migratedRows", report.migratedRows)
            put("anomalousRows", report.anomalousRows)
            put("legacyMissingSourceRows", report.legacyMissingSourceRows)
            put("notes", JSONArray(report.notes))
        }
        val settingCv = ContentValues().apply {
            put("key", "last_migration_report")
            put("value_json", reportJson.toString())
        }
        db.insertWithOnConflict("settings", null, settingCv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun <T> runInTransaction(block: () -> T): T = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun saveTransaction(tx: Transaction) = synchronized(lock) {
        insertTransaction(writableDatabase, tx)
    }

    override fun saveTransactionBundle(
        transactions: List<Transaction>,
        sources: List<TransactionSource>,
        links: List<TransactionSourceLink>,
        change: TransactionChange?
    ) = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in sources) insertSource(db, s)
            for (tx in transactions) insertTransaction(db, tx)
            for (l in links) insertLink(db, l)
            if (change != null) insertChange(db, change)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getTransaction(txId: String): Transaction? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM transactions WHERE id = ?", arrayOf(txId)).use { c ->
            if (c.moveToFirst()) cursorToTx(c) else null
        }
    }

    override fun listTransactions(limit: Int): List<Transaction> = synchronized(lock) {
        listTransactionsFromDb(readableDatabase, limit)
    }

    override fun listSourcesForTransaction(txId: String): List<Pair<TransactionSource, TransactionSourceLink>> = synchronized(lock) {
        val out = mutableListOf<Pair<TransactionSource, TransactionSourceLink>>()
        readableDatabase.rawQuery(
            """
            SELECT s.*, l.transaction_id AS l_tx_id, l.source_id AS l_src_id, l.link_role AS l_role, l.candidate_id AS l_cand_id
            FROM transaction_source_links l
            INNER JOIN transaction_sources s ON s.id = l.source_id
            WHERE l.transaction_id = ?
            ORDER BY s.observed_at_ms ASC
            """.trimIndent(),
            arrayOf(txId)
        ).use { c ->
            while (c.moveToNext()) {
                val src = cursorToSource(c)
                val link = TransactionSourceLink(
                    transactionId = c.getString(c.getColumnIndexOrThrow("l_tx_id")),
                    sourceId = c.getString(c.getColumnIndexOrThrow("l_src_id")),
                    linkRole = runCatching { SourceLinkRole.valueOf(c.getString(c.getColumnIndexOrThrow("l_role"))) }
                        .getOrDefault(SourceLinkRole.PRIMARY_PAYMENT),
                    candidateId = c.getString(c.getColumnIndexOrThrow("l_cand_id"))
                )
                out.add(src to link)
            }
        }
        out
    }

    override fun listLinksForSource(sourceId: String): List<TransactionSourceLink> = synchronized(lock) {
        val out = mutableListOf<TransactionSourceLink>()
        readableDatabase.rawQuery(
            "SELECT * FROM transaction_source_links WHERE source_id = ?",
            arrayOf(sourceId)
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToLink(c))
        }
        out
    }

    override fun hasActiveSourceExternalId(externalId: String): Boolean = synchronized(lock) {
        if (externalId.isBlank()) return@synchronized false
        readableDatabase.rawQuery(
            """
            SELECT 1
            FROM transaction_sources s
            INNER JOIN transaction_source_links l ON l.source_id = s.id
            INNER JOIN transactions t ON t.id = l.transaction_id
            WHERE s.external_id = ?
              AND t.status != 'REVERTED'
            LIMIT 1
            """.trimIndent(),
            arrayOf(externalId)
        ).use { c -> c.moveToFirst() }
    }

    override fun hasActiveSourceFingerprint(shortFingerprint: String): Boolean = synchronized(lock) {
        if (shortFingerprint.isBlank()) return@synchronized false
        readableDatabase.rawQuery(
            """
            SELECT 1
            FROM transaction_sources s
            INNER JOIN transaction_source_links l ON l.source_id = s.id
            INNER JOIN transactions t ON t.id = l.transaction_id
            WHERE s.short_fingerprint = ?
              AND t.status != 'REVERTED'
            LIMIT 1
            """.trimIndent(),
            arrayOf(shortFingerprint)
        ).use { c -> c.moveToFirst() }
    }

    override fun findBackfillCandidates(
        amountMinor: Long,
        currency: String,
        sinceMs: Long,
        untilMs: Long
    ): List<Transaction> = synchronized(lock) {
        val out = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status IN ('RECORDED', 'NEEDS_REVIEW')
              AND currency = ?
              AND amount_minor = ?
              AND occurred_at_ms BETWEEN ? AND ?
            ORDER BY occurred_at_ms DESC
            """.trimIndent(),
            arrayOf(currency, amountMinor.toString(), sinceMs.toString(), untilMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToTx(c))
        }
        out
    }

    override fun listAllSources(): List<TransactionSource> = synchronized(lock) {
        listAllSourcesFromDb(readableDatabase)
    }

    override fun listAllLinks(): List<TransactionSourceLink> = synchronized(lock) {
        listAllLinksFromDb(readableDatabase)
    }

    override fun listChanges(limit: Int): List<TransactionChange> = synchronized(lock) {
        listChangesFromDb(readableDatabase, limit)
    }

    override fun getChange(opId: String): TransactionChange? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM transaction_changes WHERE op_id = ?", arrayOf(opId)).use { c ->
            if (c.moveToFirst()) cursorToChange(c) else null
        }
    }

    override fun saveChange(change: TransactionChange) = synchronized(lock) {
        insertChange(writableDatabase, change)
    }

    override fun deleteLinksForTransaction(txId: String) = synchronized(lock) {
        writableDatabase.delete("transaction_source_links", "transaction_id = ?", arrayOf(txId))
        Unit
    }

    override fun saveLinks(links: List<TransactionSourceLink>) = synchronized(lock) {
        val db = writableDatabase
        for (l in links) insertLink(db, l)
    }

    override fun listUserRules(): List<UserMerchantRule> = synchronized(lock) {
        listUserRulesFromDb(readableDatabase)
    }

    override fun saveUserRule(rule: UserMerchantRule) = synchronized(lock) {
        insertUserRule(writableDatabase, rule)
    }

    override fun deleteUserRule(ruleId: String) = synchronized(lock) {
        writableDatabase.delete("user_rules", "id = ?", arrayOf(ruleId))
        Unit
    }

    override fun findRecentMirrorCandidates(
        amountMinor: Long,
        currency: String,
        oppositeDirection: TxDirection,
        sinceMs: Long,
        untilMs: Long
    ): List<Transaction> = synchronized(lock) {
        val out = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status = 'RECORDED'
              AND tx_type = ?
              AND currency = ?
              AND amount_minor = ?
              AND occurred_at_ms BETWEEN ? AND ?
            ORDER BY occurred_at_ms DESC
            """.trimIndent(),
            arrayOf(oppositeDirection.name, currency, amountMinor.toString(), sinceMs.toString(), untilMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(cursorToTx(c))
        }
        out
    }

    override fun findRecentExpenseForRefund(
        amountMinor: Long,
        currency: String,
        merchant: String,
        sinceMs: Long
    ): Transaction? = synchronized(lock) {
        val candidates = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status = 'RECORDED'
              AND tx_type = 'EXPENSE'
              AND currency = ?
              AND amount_minor >= ?
              AND occurred_at_ms >= ?
            ORDER BY occurred_at_ms DESC
            LIMIT 25
            """.trimIndent(),
            arrayOf(currency, amountMinor.toString(), sinceMs.toString())
        ).use { c ->
            while (c.moveToNext()) candidates.add(cursorToTx(c))
        }
        candidates.firstOrNull { tx ->
            merchant.isNotBlank() && merchant != "未知商户" &&
                (merchant.contains(tx.merchant) || tx.merchant.contains(merchant))
        }
    }

    override fun findRecentSplitFundedCandidate(
        partAmountMinor: Long,
        currency: String,
        sinceMs: Long,
        untilMs: Long
    ): Pair<Transaction, TransactionSource>? = synchronized(lock) {
        val recentTxs = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status IN ('RECORDED', 'NEEDS_REVIEW')
              AND tx_type = 'EXPENSE'
              AND currency = ?
              AND amount_minor > ?
              AND occurred_at_ms BETWEEN ? AND ?
            ORDER BY occurred_at_ms DESC
            LIMIT 10
            """.trimIndent(),
            arrayOf(currency, partAmountMinor.toString(), sinceMs.toString(), untilMs.toString())
        ).use { c ->
            while (c.moveToNext()) recentTxs.add(cursorToTx(c))
        }
        for (tx in recentTxs) {
            val paired = listSourcesForTransaction(tx.id)
            val alreadyLinkedSplitIds = paired.filter { it.second.linkRole == SourceLinkRole.SPLIT_FUNDING }
                .mapNotNull { it.second.candidateId }.toSet()
            for ((src, _) in paired) {
                val matchedPart = src.amountCandidates.firstOrNull { cand ->
                    cand.role == AmountRole.SPLIT_PART &&
                        cand.currency == currency &&
                        cand.amountMinor == partAmountMinor &&
                        cand.candidateId !in alreadyLinkedSplitIds
                }
                if (matchedPart != null) {
                    return@synchronized tx to src
                }
            }
        }
        null
    }

    override fun enqueueOffline(item: OfflineQueueItem) = synchronized(lock) {
        insertOfflineItem(writableDatabase, item)
    }

    override fun listOfflineQueue(): List<OfflineQueueItem> = synchronized(lock) {
        listOfflineQueueFromDb(readableDatabase)
    }

    override fun removeOfflineItem(queueId: String) = synchronized(lock) {
        writableDatabase.delete("offline_queue", "queue_id = ?", arrayOf(queueId))
        Unit
    }

    override fun addLog(stage: String, message: String, sanitizedSample: String) = synchronized(lock) {
        val cv = ContentValues().apply {
            put("created_at_ms", System.currentTimeMillis())
            put("stage", stage)
            put("message", message)
            put("sanitized_sample", sanitizedSample)
        }
        writableDatabase.insert("audit_logs", null, cv)
        writableDatabase.execSQL(
            "DELETE FROM audit_logs WHERE id NOT IN (SELECT id FROM audit_logs ORDER BY id DESC LIMIT 250)"
        )
    }

    override fun listLogs(limit: Int): List<AuditLogEntry> = synchronized(lock) {
        val list = mutableListOf<AuditLogEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    AuditLogEntry(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
                        stage = c.getString(c.getColumnIndexOrThrow("stage")),
                        message = c.getString(c.getColumnIndexOrThrow("message")),
                        sanitizedSample = c.getString(c.getColumnIndexOrThrow("sanitized_sample"))
                    )
                )
            }
        }
        list
    }

    override fun loadProfile(): UserProfile = synchronized(lock) {
        loadProfileFromDb(readableDatabase)
    }

    override fun saveProfile(profile: UserProfile) = synchronized(lock) {
        saveProfileToDb(writableDatabase, profile)
    }

    override fun loadAiConfig(): AiConfig = synchronized(lock) {
        val defaultCfg = AiConfig(
            mode = AiConfig.MODE_LOCAL,
            baseUrl = BuildConfig.DEFAULT_AI_BASE_URL,
            apiKey = BuildConfig.DEFAULT_AI_API_KEY,
            model = BuildConfig.DEFAULT_AI_MODEL
        )
        val raw = loadSettingJson(readableDatabase, "ai_config") ?: return defaultCfg
        val obj = JSONObject(raw)
        val endpoint = obj.optString("baseUrl", defaultCfg.baseUrl).ifBlank { defaultCfg.baseUrl }
        AiConfig(
            mode = obj.optString("mode", defaultCfg.mode),
            baseUrl = endpoint,
            apiKey = effectiveKey(obj.optString("apiKey", ""), endpoint, defaultCfg.baseUrl, defaultCfg.apiKey),
            model = obj.optString("model", defaultCfg.model).ifBlank { defaultCfg.model },
            timeoutMs = obj.optInt("timeoutMs", 15000)
        )
    }

    override fun saveAiConfig(config: AiConfig) = synchronized(lock) {
        val obj = JSONObject().apply {
            put("mode", config.mode)
            put("baseUrl", config.baseUrl)
            put("apiKey", config.apiKey)
            put("model", config.model)
            put("timeoutMs", config.timeoutMs)
        }
        saveSettingJson(writableDatabase, "ai_config", obj.toString())
    }

    override fun getDbSizeBytes(): Long {
        val file = appContext.getDatabasePath(dbName)
        return if (file.exists()) file.length() else 0L
    }

    override fun clearLedgerAndLogs() = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transaction_source_links", null, null)
            db.delete("transaction_sources", null, null)
            db.delete("transaction_changes", null, null)
            db.delete("transactions", null, null)
            db.delete("offline_queue", null, null)
            db.delete("audit_logs", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun exportLogicalSnapshot(nowMs: Long): BackupSnapshot = synchronized(lock) {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val profile = loadProfileFromDb(db)
            val userRules = listUserRulesFromDb(db)
            val transactions = listTransactionsFromDb(db, Int.MAX_VALUE)
            val sources = listAllSourcesFromDb(db)
            val links = listAllLinksFromDb(db)
            val changes = listChangesFromDb(db, Int.MAX_VALUE)
            val pendingQueue = listOfflineQueueFromDb(db)
            val checks = BackupCodec.computeCurrencyChecks(transactions)
            BackupSnapshot(
                schemaVersion = BackupCodec.SNAPSHOT_SCHEMA_VERSION,
                exportedAtMs = nowMs,
                profile = profile,
                userRules = userRules,
                transactions = transactions,
                sources = sources,
                sourceLinks = links,
                changes = changes,
                pendingQueue = pendingQueue,
                currencyChecks = checks
            )
        } finally {
            db.endTransaction()
        }
    }

    override fun restoreFromSnapshot(
        snapshot: BackupSnapshot,
        beforeSwapHook: (() -> Unit)?,
        duringSwapHook: (() -> Unit)?
    ): RestoreReport = synchronized(lock) {
        // Validate logical checks first before touching any disk file
        val expectedChecks = BackupCodec.computeCurrencyChecks(snapshot.transactions)
        if (snapshot.currencyChecks != expectedChecks) {
            throw BackupException("恢复前校验失败：分币种汇总不匹配")
        }

        val preservedAiConfigRaw = runCatching { loadSettingJson(readableDatabase, "ai_config") }.getOrNull()

        val tmpDbName = "${dbName}.restore_tmp_${UUID.randomUUID().toString().take(8)}"
        val tmpDbFile = appContext.getDatabasePath(tmpDbName)
        deleteDbAndSidecars(tmpDbFile)

        // Step 1: Materialize and verify the complete snapshot inside an isolated temporary SQLite DB
        val tmpHelper = LedgerDatabase(appContext, tmpDbName)
        try {
            val tmpDb = tmpHelper.writableDatabase
            tmpDb.beginTransaction()
            try {
                saveProfileToDb(tmpDb, snapshot.profile)
                if (preservedAiConfigRaw != null) {
                    saveSettingJson(tmpDb, "ai_config", preservedAiConfigRaw)
                }
                for (r in snapshot.userRules) insertUserRule(tmpDb, r)
                for (s in snapshot.sources) insertSource(tmpDb, s)
                for (tx in snapshot.transactions) insertTransaction(tmpDb, tx)
                for (l in snapshot.sourceLinks) insertLink(tmpDb, l)
                for (ch in snapshot.changes) insertChange(tmpDb, ch)
                for (q in snapshot.pendingQueue) insertOfflineItem(tmpDb, q)
                tmpDb.setTransactionSuccessful()
            } finally {
                tmpDb.endTransaction()
            }

            // Verify FK integrity and recomputed totals directly from real SQLite
            tmpDb.rawQuery("PRAGMA foreign_key_check", null).use { fk ->
                if (fk.moveToFirst()) {
                    throw BackupException("临时库外键完整性校验失败")
                }
            }
            val verifiedTxs = listTransactionsFromDb(tmpDb, Int.MAX_VALUE)
            val verifiedChecks = BackupCodec.computeCurrencyChecks(verifiedTxs)
            if (verifiedTxs.size != snapshot.transactions.size || verifiedChecks != expectedChecks) {
                throw BackupException("临时库账单数量或分币种汇总校验失败")
            }
            checkpointAndClose(tmpHelper)
        } catch (e: Exception) {
            runCatching { tmpHelper.close() }
            deleteDbAndSidecars(tmpDbFile)
            if (e is BackupException) throw e
            throw BackupException("写入临时恢复库失败：${e.message}", e)
        }

        // Optional fault injection before atomic swap (verifies original DB stays intact)
        try {
            beforeSwapHook?.invoke()
        } catch (e: Exception) {
            deleteDbAndSidecars(tmpDbFile)
            throw BackupException("恢复在切换前中断：${e.message}", e)
        }

        // Step 2: Atomic file swap with rollback backup
        val liveFile = appContext.getDatabasePath(dbName)
        val bakFile = File(liveFile.parentFile, "${dbName}.rollback_bak")
        deleteDbAndSidecars(bakFile)

        checkpointAndClose(this)

        var movedLiveToBak = false
        try {
            if (liveFile.exists()) {
                if (!liveFile.renameTo(bakFile)) {
                    throw BackupException("无法保留原账本回滚副本")
                }
                movedLiveToBak = true
            }
            deleteSidecarsOnly(liveFile)

            duringSwapHook?.invoke()

            if (!tmpDbFile.renameTo(liveFile)) {
                throw BackupException("无法切换新恢复账本文件")
            }
            deleteSidecarsOnly(tmpDbFile)

            // Reopen and run final read verification on live DB
            val liveCheckTxs = listTransactionsFromDb(readableDatabase, Int.MAX_VALUE)
            val liveChecks = BackupCodec.computeCurrencyChecks(liveCheckTxs)
            if (liveCheckTxs.size != snapshot.transactions.size || liveChecks != expectedChecks) {
                throw BackupException("切换后账本复检失败")
            }
            deleteDbAndSidecars(bakFile)
            RestoreReport(
                transactionCount = snapshot.transactions.size,
                sourceCount = snapshot.sources.size,
                linkCount = snapshot.sourceLinks.size,
                changeCount = snapshot.changes.size,
                pendingQueueCount = snapshot.pendingQueue.size,
                currencyChecks = liveChecks
            )
        } catch (e: Exception) {
            runCatching { close() }
            deleteDbAndSidecars(liveFile)
            if (movedLiveToBak && bakFile.exists()) {
                bakFile.renameTo(liveFile)
            }
            deleteDbAndSidecars(tmpDbFile)
            if (e is BackupException) throw e
            throw BackupException("账本恢复失败，已回滚保留原账本：${e.message}", e)
        }
    }

    private fun checkpointAndClose(helper: SQLiteOpenHelper) {
        runCatching {
            helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        runCatching { helper.close() }
    }

    private fun deleteSidecarsOnly(dbFile: File) {
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()
    }

    private fun deleteDbAndSidecars(dbFile: File) {
        dbFile.delete()
        deleteSidecarsOnly(dbFile)
    }

    private fun insertTransaction(db: SQLiteDatabase, tx: Transaction) {
        val cv = ContentValues().apply {
            put("id", tx.id)
            put("occurred_at_ms", tx.occurredAtMs)
            put("observed_at_ms", tx.observedAtMs)
            put("updated_at_ms", tx.updatedAtMs)
            put("revision", tx.revision)
            put("tx_type", tx.direction.name)
            put("amount_minor", tx.amountMinor)
            put("currency", tx.currency)
            put("scale", tx.scale)
            if (tx.settlementAmountMinor != null) put("settlement_amount_minor", tx.settlementAmountMinor) else putNull("settlement_amount_minor")
            if (tx.settlementCurrency != null) put("settlement_currency", tx.settlementCurrency) else putNull("settlement_currency")
            put("settlement_scale", tx.settlementScale)
            put("settlement_is_estimated", if (tx.settlementIsEstimated) 1 else 0)
            put("merchant", tx.merchant)
            put("item_summary", tx.itemSummary)
            put("category", tx.category)
            put("payment_method", tx.paymentMethod)
            put("source_channels", JSONArray(tx.sourceChannels).toString())
            put("merged_notifications", tx.mergedNotifications)
            put("status", tx.status.name)
            put("ignore_reason", tx.ignoreReason)
            put("deep_link", tx.deepLink)
            put("deep_link_label", tx.deepLinkLabel)
            put("sanitized_text", tx.sanitizedText)
            put("ai_mode_used", tx.aiModeUsed)
            put("ai_reasoning", tx.aiReasoning)
            if (tx.relatedTxId != null) put("related_tx_id", tx.relatedTxId) else putNull("related_tx_id")
            put("rescued_by_user", if (tx.rescuedByUser) 1 else 0)
            put("legacy_merged_count", tx.legacyMergedCount)
            put("legacy_missing_sources", if (tx.legacyMissingSources) 1 else 0)
        }
        db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertSource(db: SQLiteDatabase, s: TransactionSource) {
        val cv = ContentValues().apply {
            put("id", s.id)
            put("source_type", s.sourceType)
            put("app_package", s.appPackage)
            put("app_name", s.appName)
            if (s.externalId != null) put("external_id", s.externalId) else putNull("external_id")
            put("observed_at_ms", s.observedAtMs)
            if (s.sourceEventTimeMs != null) put("source_event_time_ms", s.sourceEventTimeMs) else putNull("source_event_time_ms")
            put("amount_candidates_json", AmountCandidate.listToJson(s.amountCandidates).toString())
            put("matcher_version", s.matcherVersion)
            put("short_fingerprint", s.shortFingerprint)
            if (s.cardTail != null) put("card_tail", s.cardTail) else putNull("card_tail")
            put("merchant_hint", s.merchantHint)
            put("channel_label", s.channelLabel)
        }
        db.insertWithOnConflict("transaction_sources", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertLink(db: SQLiteDatabase, l: TransactionSourceLink) {
        val cv = ContentValues().apply {
            put("transaction_id", l.transactionId)
            put("source_id", l.sourceId)
            put("link_role", l.linkRole.name)
            if (l.candidateId != null) put("candidate_id", l.candidateId) else putNull("candidate_id")
        }
        db.insertWithOnConflict("transaction_source_links", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertChange(db: SQLiteDatabase, ch: TransactionChange) {
        val cv = ContentValues().apply {
            put("op_id", ch.opId)
            put("op_type", ch.opType.name)
            put("affected_json", JSONArray(ch.affectedTxIds).toString())
            put("before_revisions_json", BackupCodec.intMapToJson(ch.beforeRevisions).toString())
            put("after_revisions_json", BackupCodec.intMapToJson(ch.afterRevisions).toString())
            put("inverse_payload_json", ch.inversePayloadJson)
            put("created_at_ms", ch.createdAtMs)
        }
        db.insertWithOnConflict("transaction_changes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertUserRule(db: SQLiteDatabase, rule: UserMerchantRule) {
        val cv = ContentValues().apply {
            put("id", rule.id)
            put("source_scope", rule.sourceScope)
            put("normalized_merchant", rule.normalizedMerchant)
            put("category", rule.category)
            put("rule_version", rule.ruleVersion)
            put("created_at_ms", rule.createdAtMs)
        }
        db.insertWithOnConflict("user_rules", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertOfflineItem(db: SQLiteDatabase, item: OfflineQueueItem) {
        val cv = ContentValues().apply {
            put("queue_id", item.queueId)
            put("enqueued_at_ms", item.enqueuedAtMs)
            put("payload_json", BackupCodec.offlineItemToJson(item).toString())
        }
        db.insertWithOnConflict("offline_queue", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun listTransactionsFromDb(db: SQLiteDatabase, limit: Int): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val cursor = if (limit <= 0 || limit == Int.MAX_VALUE) {
            db.rawQuery("SELECT * FROM transactions ORDER BY occurred_at_ms DESC", null)
        } else {
            db.rawQuery(
                "SELECT * FROM transactions ORDER BY occurred_at_ms DESC LIMIT ?",
                arrayOf(limit.toString())
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) list.add(cursorToTx(c))
        }
        return list
    }

    private fun listAllSourcesFromDb(db: SQLiteDatabase): List<TransactionSource> {
        val out = mutableListOf<TransactionSource>()
        db.rawQuery("SELECT * FROM transaction_sources ORDER BY observed_at_ms ASC", null).use { c ->
            while (c.moveToNext()) out.add(cursorToSource(c))
        }
        return out
    }

    private fun listAllLinksFromDb(db: SQLiteDatabase): List<TransactionSourceLink> {
        val out = mutableListOf<TransactionSourceLink>()
        db.rawQuery("SELECT * FROM transaction_source_links", null).use { c ->
            while (c.moveToNext()) out.add(cursorToLink(c))
        }
        return out
    }

    private fun listChangesFromDb(db: SQLiteDatabase, limit: Int): List<TransactionChange> {
        val out = mutableListOf<TransactionChange>()
        val cursor = if (limit <= 0 || limit == Int.MAX_VALUE) {
            db.rawQuery("SELECT * FROM transaction_changes ORDER BY created_at_ms DESC", null)
        } else {
            db.rawQuery("SELECT * FROM transaction_changes ORDER BY created_at_ms DESC LIMIT ?", arrayOf(limit.toString()))
        }
        cursor.use { c ->
            while (c.moveToNext()) out.add(cursorToChange(c))
        }
        return out
    }

    private fun listUserRulesFromDb(db: SQLiteDatabase): List<UserMerchantRule> {
        val out = mutableListOf<UserMerchantRule>()
        db.rawQuery("SELECT * FROM user_rules ORDER BY created_at_ms DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UserMerchantRule(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        sourceScope = c.getString(c.getColumnIndexOrThrow("source_scope")),
                        normalizedMerchant = c.getString(c.getColumnIndexOrThrow("normalized_merchant")),
                        category = c.getString(c.getColumnIndexOrThrow("category")),
                        ruleVersion = c.getInt(c.getColumnIndexOrThrow("rule_version")),
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms"))
                    )
                )
            }
        }
        return out
    }

    private fun listOfflineQueueFromDb(db: SQLiteDatabase): List<OfflineQueueItem> {
        val list = mutableListOf<OfflineQueueItem>()
        db.rawQuery("SELECT * FROM offline_queue ORDER BY enqueued_at_ms ASC", null).use { c ->
            while (c.moveToNext()) {
                val qid = c.getString(c.getColumnIndexOrThrow("queue_id"))
                val enq = c.getLong(c.getColumnIndexOrThrow("enqueued_at_ms"))
                val obj = JSONObject(c.getString(c.getColumnIndexOrThrow("payload_json"))).apply {
                    if (!has("queueId")) put("queueId", qid)
                    if (!has("enqueuedAtMs")) put("enqueuedAtMs", enq)
                }
                list.add(BackupCodec.offlineItemFromJson(obj))
            }
        }
        return list
    }

    private fun loadProfileFromDb(db: SQLiteDatabase): UserProfile {
        val raw = loadSettingJson(db, "user_profile") ?: return UserProfile()
        val obj = JSONObject(raw)
        return UserProfile(
            realName = obj.optString("realName", ""),
            aliases = BackupCodec.jsonArrayToStrings(obj.optJSONArray("aliases")),
            ownCardTails = BackupCodec.jsonArrayToStrings(obj.optJSONArray("ownCardTails")),
            brokers = BackupCodec.jsonArrayToStrings(obj.optJSONArray("brokers")).ifEmpty { UserProfile().brokers }
        )
    }

    private fun saveProfileToDb(db: SQLiteDatabase, profile: UserProfile) {
        val obj = JSONObject().apply {
            put("realName", profile.realName)
            put("aliases", JSONArray(profile.aliases))
            put("ownCardTails", JSONArray(profile.ownCardTails))
            put("brokers", JSONArray(profile.brokers))
        }
        saveSettingJson(db, "user_profile", obj.toString())
    }

    private fun saveSettingJson(db: SQLiteDatabase, key: String, json: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value_json", json)
        }
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun loadSettingJson(db: SQLiteDatabase, key: String): String? {
        return db.rawQuery("SELECT value_json FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun cursorToTx(c: Cursor): Transaction {
        val settlementAmtIdx = c.getColumnIndexOrThrow("settlement_amount_minor")
        val settlementCurrIdx = c.getColumnIndexOrThrow("settlement_currency")
        val relatedIdx = c.getColumnIndexOrThrow("related_tx_id")
        return Transaction(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            occurredAtMs = c.getLong(c.getColumnIndexOrThrow("occurred_at_ms")),
            observedAtMs = c.getLong(c.getColumnIndexOrThrow("observed_at_ms")),
            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updated_at_ms")),
            revision = c.getInt(c.getColumnIndexOrThrow("revision")),
            direction = runCatching {
                TxDirection.valueOf(c.getString(c.getColumnIndexOrThrow("tx_type")))
            }.getOrDefault(TxDirection.EXPENSE),
            amountMinor = c.getLong(c.getColumnIndexOrThrow("amount_minor")),
            currency = c.getString(c.getColumnIndexOrThrow("currency")),
            scale = c.getInt(c.getColumnIndexOrThrow("scale")),
            settlementAmountMinor = if (c.isNull(settlementAmtIdx)) null else c.getLong(settlementAmtIdx),
            settlementCurrency = if (c.isNull(settlementCurrIdx)) null else c.getString(settlementCurrIdx),
            settlementScale = c.getInt(c.getColumnIndexOrThrow("settlement_scale")),
            settlementIsEstimated = c.getInt(c.getColumnIndexOrThrow("settlement_is_estimated")) == 1,
            merchant = c.getString(c.getColumnIndexOrThrow("merchant")),
            itemSummary = c.getString(c.getColumnIndexOrThrow("item_summary")),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            paymentMethod = c.getString(c.getColumnIndexOrThrow("payment_method")),
            sourceChannels = BackupCodec.jsonArrayToStrings(JSONArray(c.getString(c.getColumnIndexOrThrow("source_channels")))),
            mergedNotifications = c.getInt(c.getColumnIndexOrThrow("merged_notifications")),
            status = runCatching {
                TxStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")))
            }.getOrDefault(TxStatus.RECORDED),
            ignoreReason = c.getString(c.getColumnIndexOrThrow("ignore_reason")),
            deepLink = c.getString(c.getColumnIndexOrThrow("deep_link")),
            deepLinkLabel = c.getString(c.getColumnIndexOrThrow("deep_link_label")),
            sanitizedText = c.getString(c.getColumnIndexOrThrow("sanitized_text")),
            aiModeUsed = c.getString(c.getColumnIndexOrThrow("ai_mode_used")),
            aiReasoning = c.getString(c.getColumnIndexOrThrow("ai_reasoning")),
            relatedTxId = if (c.isNull(relatedIdx)) null else c.getString(relatedIdx),
            rescuedByUser = c.getInt(c.getColumnIndexOrThrow("rescued_by_user")) == 1,
            legacyMergedCount = c.getInt(c.getColumnIndexOrThrow("legacy_merged_count")),
            legacyMissingSources = c.getInt(c.getColumnIndexOrThrow("legacy_missing_sources")) == 1
        )
    }

    private fun cursorToSource(c: Cursor): TransactionSource {
        val extIdx = c.getColumnIndexOrThrow("external_id")
        val evtIdx = c.getColumnIndexOrThrow("source_event_time_ms")
        val tailIdx = c.getColumnIndexOrThrow("card_tail")
        return TransactionSource(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            sourceType = c.getString(c.getColumnIndexOrThrow("source_type")),
            appPackage = c.getString(c.getColumnIndexOrThrow("app_package")),
            appName = c.getString(c.getColumnIndexOrThrow("app_name")),
            externalId = if (c.isNull(extIdx)) null else c.getString(extIdx),
            observedAtMs = c.getLong(c.getColumnIndexOrThrow("observed_at_ms")),
            sourceEventTimeMs = if (c.isNull(evtIdx)) null else c.getLong(evtIdx),
            amountCandidates = AmountCandidate.listFromJson(JSONArray(c.getString(c.getColumnIndexOrThrow("amount_candidates_json")))),
            matcherVersion = c.getString(c.getColumnIndexOrThrow("matcher_version")),
            shortFingerprint = c.getString(c.getColumnIndexOrThrow("short_fingerprint")),
            cardTail = if (c.isNull(tailIdx)) null else c.getString(tailIdx),
            merchantHint = c.getString(c.getColumnIndexOrThrow("merchant_hint")),
            channelLabel = c.getString(c.getColumnIndexOrThrow("channel_label"))
        )
    }

    private fun cursorToLink(c: Cursor): TransactionSourceLink {
        val candIdx = c.getColumnIndexOrThrow("candidate_id")
        return TransactionSourceLink(
            transactionId = c.getString(c.getColumnIndexOrThrow("transaction_id")),
            sourceId = c.getString(c.getColumnIndexOrThrow("source_id")),
            linkRole = runCatching { SourceLinkRole.valueOf(c.getString(c.getColumnIndexOrThrow("link_role"))) }
                .getOrDefault(SourceLinkRole.PRIMARY_PAYMENT),
            candidateId = if (c.isNull(candIdx)) null else c.getString(candIdx)
        )
    }

    private fun cursorToChange(c: Cursor): TransactionChange {
        return TransactionChange(
            opId = c.getString(c.getColumnIndexOrThrow("op_id")),
            opType = runCatching { ChangeOpType.valueOf(c.getString(c.getColumnIndexOrThrow("op_type"))) }
                .getOrDefault(ChangeOpType.EDIT),
            affectedTxIds = BackupCodec.jsonArrayToStrings(JSONArray(c.getString(c.getColumnIndexOrThrow("affected_json")))),
            beforeRevisions = BackupCodec.jsonToIntMap(JSONObject(c.getString(c.getColumnIndexOrThrow("before_revisions_json")))),
            afterRevisions = BackupCodec.jsonToIntMap(JSONObject(c.getString(c.getColumnIndexOrThrow("after_revisions_json")))),
            inversePayloadJson = c.getString(c.getColumnIndexOrThrow("inverse_payload_json")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms"))
        )
    }

    companion object {
        const val DB_NAME = "wallet_watcher.db"
        const val DB_VERSION = 3
    }
}
