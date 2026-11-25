package com.walletwatcher.app.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.walletwatcher.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Storage contract so LedgerEngine can be unit-tested cleanly on pure JVM without Android SQLite.
 */
interface LedgerStore {
    fun saveTransaction(tx: Transaction)
    fun getTransaction(txId: String): Transaction?
    fun listTransactions(limit: Int = 300): List<Transaction>
    fun findRecentMirrorCandidate(amount: Double, oppositeDirection: TxDirection, sinceMs: Long): Transaction?
    fun findRecentExpenseForRefund(amount: Double, merchant: String, sinceMs: Long): Transaction?
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
}

class LedgerDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION), LedgerStore {

    private val appContext = context.applicationContext
    private val lock = Any()

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
        db.execSQL(
            """
            CREATE TABLE offline_queue (
                queue_id TEXT PRIMARY KEY,
                enqueued_at_ms INTEGER NOT NULL,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE audit_logs (
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
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at_ms DESC)")
    }

    override fun saveTransaction(tx: Transaction) = synchronized(lock) {
        val cv = ContentValues().apply {
            put("id", tx.id)
            put("created_at_ms", tx.createdAtMs)
            put("direction", tx.direction.name)
            put("amount", tx.amount)
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
            put("related_tx_id", tx.relatedTxId)
            put("rescued_by_user", if (tx.rescuedByUser) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun getTransaction(txId: String): Transaction? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT * FROM transactions WHERE id = ?", arrayOf(txId)).use { c ->
            if (c.moveToFirst()) cursorToTx(c) else null
        }
    }

    override fun listTransactions(limit: Int): List<Transaction> = synchronized(lock) {
        val list = mutableListOf<Transaction>()
        val cursor = if (limit <= 0 || limit == Int.MAX_VALUE) {
            readableDatabase.rawQuery("SELECT * FROM transactions ORDER BY created_at_ms DESC", null)
        } else {
            readableDatabase.rawQuery(
                "SELECT * FROM transactions ORDER BY created_at_ms DESC LIMIT ?",
                arrayOf(limit.toString())
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) list.add(cursorToTx(c))
        }
        list
    }

    override fun findRecentMirrorCandidate(
        amount: Double,
        oppositeDirection: TxDirection,
        sinceMs: Long
    ): Transaction? = synchronized(lock) {
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status = 'RECORDED'
              AND direction = ?
              AND ABS(amount - CAST(? AS REAL)) < 0.005
              AND created_at_ms >= CAST(? AS INTEGER)
            ORDER BY created_at_ms DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(oppositeDirection.name, amount.toString(), sinceMs.toString())
        ).use { c ->
            if (c.moveToFirst()) cursorToTx(c) else null
        }
    }

    override fun findRecentExpenseForRefund(
        amount: Double,
        merchant: String,
        sinceMs: Long
    ): Transaction? = synchronized(lock) {
        val candidates = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM transactions
            WHERE status = 'RECORDED'
              AND direction = 'EXPENSE'
              AND amount >= CAST(? AS REAL) - 0.005
              AND created_at_ms >= CAST(? AS INTEGER)
            ORDER BY created_at_ms DESC
            LIMIT 20
            """.trimIndent(),
            arrayOf(amount.toString(), sinceMs.toString())
        ).use { c ->
            while (c.moveToNext()) candidates.add(cursorToTx(c))
        }
        candidates.firstOrNull { tx ->
            merchant.isNotBlank() && merchant != "未知商户" &&
                (merchant.contains(tx.merchant) || tx.merchant.contains(merchant))
        }
    }

    override fun enqueueOffline(item: OfflineQueueItem) = synchronized(lock) {
        val payload = item.payload
        val json = JSONObject().apply {
            put("failures", item.failures)
            put("groupId", payload.groupId)
            put("createdAtMs", payload.createdAtMs)
            put("amount", payload.amount ?: JSONObject.NULL)
            put("roughDirection", payload.roughDirection)
            put("paymentMethod", payload.paymentMethod)
            put("sourceChannels", JSONArray(payload.sourceChannels))
            put("mergedNotifications", payload.mergedNotifications)
            put("sanitizedText", payload.sanitizedText)
            put("redactionNotes", JSONArray(payload.redactionNotes))
            put("preInternalReason", payload.preInternalReason ?: JSONObject.NULL)
            put("isZeroAuth", payload.isZeroAuth)
            put("deepLink", payload.deepLink)
            put("deepLinkLabel", payload.deepLinkLabel)
        }
        val cv = ContentValues().apply {
            put("queue_id", item.queueId)
            put("enqueued_at_ms", item.enqueuedAtMs)
            put("payload_json", json.toString())
        }
        writableDatabase.insertWithOnConflict("offline_queue", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun listOfflineQueue(): List<OfflineQueueItem> = synchronized(lock) {
        val list = mutableListOf<OfflineQueueItem>()
        readableDatabase.rawQuery("SELECT * FROM offline_queue ORDER BY enqueued_at_ms ASC", null).use { c ->
            while (c.moveToNext()) {
                val qid = c.getString(c.getColumnIndexOrThrow("queue_id"))
                val enq = c.getLong(c.getColumnIndexOrThrow("enqueued_at_ms"))
                val obj = JSONObject(c.getString(c.getColumnIndexOrThrow("payload_json")))
                val payload = MergedPayload(
                    groupId = obj.getString("groupId"),
                    createdAtMs = obj.getLong("createdAtMs"),
                    amount = if (obj.isNull("amount")) null else obj.getDouble("amount"),
                    roughDirection = obj.getString("roughDirection"),
                    paymentMethod = obj.getString("paymentMethod"),
                    sourceChannels = jsonArrayToStrings(obj.optJSONArray("sourceChannels")),
                    mergedNotifications = obj.optInt("mergedNotifications", 1),
                    sanitizedText = obj.optString("sanitizedText", ""),
                    redactionNotes = jsonArrayToStrings(obj.optJSONArray("redactionNotes")),
                    preInternalReason = if (obj.isNull("preInternalReason")) null else obj.optString("preInternalReason"),
                    isZeroAuth = obj.optBoolean("isZeroAuth", false),
                    deepLink = obj.optString("deepLink", ""),
                    deepLinkLabel = obj.optString("deepLinkLabel", "")
                )
                list.add(OfflineQueueItem(qid, enq, payload, obj.optInt("failures", 0)))
            }
        }
        list
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
        val raw = loadSettingJson("user_profile") ?: return UserProfile()
        val obj = JSONObject(raw)
        UserProfile(
            realName = obj.optString("realName", ""),
            aliases = jsonArrayToStrings(obj.optJSONArray("aliases")),
            ownCardTails = jsonArrayToStrings(obj.optJSONArray("ownCardTails")),
            brokers = jsonArrayToStrings(obj.optJSONArray("brokers")).ifEmpty { UserProfile().brokers }
        )
    }

    override fun saveProfile(profile: UserProfile) = synchronized(lock) {
        val obj = JSONObject().apply {
            put("realName", profile.realName)
            put("aliases", JSONArray(profile.aliases))
            put("ownCardTails", JSONArray(profile.ownCardTails))
            put("brokers", JSONArray(profile.brokers))
        }
        saveSettingJson("user_profile", obj.toString())
    }

    override fun loadAiConfig(): AiConfig = synchronized(lock) {
        val defaultCfg = AiConfig(
            mode = AiConfig.MODE_OPENAI_COMPATIBLE,
            baseUrl = BuildConfig.DEFAULT_AI_BASE_URL,
            apiKey = BuildConfig.DEFAULT_AI_API_KEY,
            model = BuildConfig.DEFAULT_AI_MODEL
        )
        val raw = loadSettingJson("ai_config") ?: return defaultCfg
        val obj = JSONObject(raw)
        AiConfig(
            mode = obj.optString("mode", defaultCfg.mode),
            baseUrl = obj.optString("baseUrl", defaultCfg.baseUrl).ifBlank { defaultCfg.baseUrl },
            apiKey = effectiveKey(obj.optString("apiKey", ""), obj.optString("baseUrl", defaultCfg.baseUrl).ifBlank { defaultCfg.baseUrl }, defaultCfg.baseUrl, defaultCfg.apiKey),
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
        saveSettingJson("ai_config", obj.toString())
    }

    override fun getDbSizeBytes(): Long {
        val file = appContext.getDatabasePath(DB_NAME)
        return if (file.exists()) file.length() else 0L
    }

    override fun clearLedgerAndLogs() = synchronized(lock) {
        writableDatabase.delete("transactions", null, null)
        writableDatabase.delete("offline_queue", null, null)
        writableDatabase.delete("audit_logs", null, null)
        Unit
    }

    private fun saveSettingJson(key: String, json: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value_json", json)
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun loadSettingJson(key: String): String? {
        return readableDatabase.rawQuery("SELECT value_json FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun cursorToTx(c: Cursor): Transaction {
        return Transaction(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
            direction = runCatching {
                TxDirection.valueOf(c.getString(c.getColumnIndexOrThrow("direction")))
            }.getOrDefault(TxDirection.EXPENSE),
            amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
            merchant = c.getString(c.getColumnIndexOrThrow("merchant")),
            itemSummary = c.getString(c.getColumnIndexOrThrow("item_summary")),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            paymentMethod = c.getString(c.getColumnIndexOrThrow("payment_method")),
            sourceChannels = jsonArrayToStrings(JSONArray(c.getString(c.getColumnIndexOrThrow("source_channels")))),
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
            relatedTxId = c.getString(c.getColumnIndexOrThrow("related_tx_id")),
            rescuedByUser = c.getInt(c.getColumnIndexOrThrow("rescued_by_user")) == 1
        )
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.optString(i))
        }
        return out
    }

    companion object {
        private const val DB_NAME = "wallet_watcher.db"
        private const val DB_VERSION = 2
    }
}

