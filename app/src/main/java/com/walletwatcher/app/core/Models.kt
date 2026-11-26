package com.walletwatcher.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

enum class TxDirection {
    EXPENSE,
    INCOME,
    REFUND,
    INTERNAL_TRANSFER,
    REIMBURSEMENT_OFFSET,
    IGNORED
}

enum class TxStatus {
    RECORDED,
    IGNORED,
    NEEDS_REVIEW,
    REVERTED
}

enum class AmountRole {
    PAID,
    PRINCIPAL,
    FEE,
    LIST_PRICE,
    DISCOUNT,
    BALANCE,
    INSTALLMENT_DUE,
    SPLIT_PART,
    SETTLEMENT,
    ESTIMATED_SETTLEMENT,
    UNKNOWN
}

enum class SourceLinkRole {
    PRIMARY_PAYMENT,
    FEE,
    SPLIT_FUNDING,
    MIRROR_COUNTERPART,
    REFUND_REF,
    OFFSET_REF,
    CORROBORATING_CHANNEL
}

enum class ChangeOpType {
    CREATE,
    MERGE,
    SPLIT,
    MARK_INTERNAL,
    RESCUE,
    MARK_OFFSET,
    EDIT,
    UNDO
}

object MoneyMath {
    private val MAX_MAJOR = BigDecimal("100000000")

    fun defaultScaleForCurrency(currency: String): Int = when (currency.uppercase(Locale.US)) {
        "JPY", "KRW" -> 0
        else -> 2
    }

    fun parseDecimalToMinor(raw: String, scale: Int = 2): Long? {
        val cleaned = raw.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        val bd = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
        if (bd < BigDecimal.ZERO || bd >= MAX_MAJOR) return null
        return runCatching {
            bd.movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }

    fun doubleToMinor(value: Double, scale: Int = 2): Long? {
        if (!value.isFinite() || value < 0.0) return null
        val bd = BigDecimal.valueOf(value)
        if (bd >= MAX_MAJOR) return null
        return runCatching {
            bd.movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }

    fun minorToDouble(minor: Long, scale: Int = 2): Double {
        return BigDecimal.valueOf(minor, scale).toDouble()
    }

    fun currencySymbol(currency: String): String = when (currency.uppercase(Locale.US)) {
        "CNY", "RMB" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "HKD" -> "HK$"
        "JPY" -> "JPY "
        else -> "${currency.uppercase(Locale.US)} "
    }

    fun formatUnsignedMinor(minor: Long, currency: String = "CNY", scale: Int = 2): String {
        val bd = BigDecimal.valueOf(abs(minor), scale).setScale(scale, RoundingMode.HALF_UP)
        return "${currencySymbol(currency)}${bd.toPlainString()}"
    }

    fun formatSignedMinor(minor: Long, currency: String = "CNY", scale: Int = 2): String {
        val prefix = if (minor < 0L) "-" else ""
        return "$prefix${formatUnsignedMinor(abs(minor), currency, scale)}"
    }
}

data class AmountCandidate(
    val candidateId: String,
    val amountMinor: Long,
    val currency: String = "CNY",
    val scale: Int = 2,
    val role: AmountRole = AmountRole.UNKNOWN,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val isEstimated: Boolean = false,
    val fundingChannelHint: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("candidateId", candidateId)
        put("amountMinor", amountMinor)
        put("currency", currency)
        put("scale", scale)
        put("role", role.name)
        put("startOffset", startOffset)
        put("endOffset", endOffset)
        put("isEstimated", isEstimated)
        put("fundingChannelHint", fundingChannelHint ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): AmountCandidate {
            val scale = obj.optInt("scale", 2).coerceIn(0, 6)
            val amountMinor = obj.getLong("amountMinor")
            require(amountMinor >= 0L) { "amountMinor must be non-negative" }
            return AmountCandidate(
                candidateId = obj.getString("candidateId"),
                amountMinor = amountMinor,
                currency = obj.optString("currency", "CNY").ifBlank { "CNY" },
                scale = scale,
                role = runCatching { AmountRole.valueOf(obj.optString("role", "UNKNOWN")) }
                    .getOrDefault(AmountRole.UNKNOWN),
                startOffset = obj.optInt("startOffset", 0),
                endOffset = obj.optInt("endOffset", 0),
                isEstimated = obj.optBoolean("isEstimated", false),
                fundingChannelHint = if (obj.isNull("fundingChannelHint")) null else obj.optString("fundingChannelHint").ifBlank { null }
            )
        }

        fun listToJson(list: List<AmountCandidate>): JSONArray = JSONArray().apply {
            for (c in list) put(c.toJson())
        }

        fun listFromJson(arr: JSONArray?): List<AmountCandidate> {
            if (arr == null) return emptyList()
            val out = ArrayList<AmountCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }
    }
}

data class TransactionSource(
    val id: String = UUID.randomUUID().toString(),
    val sourceType: String = "notification",
    val appPackage: String,
    val appName: String,
    val externalId: String? = null,
    val observedAtMs: Long,
    val sourceEventTimeMs: Long? = null,
    val amountCandidates: List<AmountCandidate> = emptyList(),
    val matcherVersion: String = "v3-local-1",
    val shortFingerprint: String = "",
    val cardTail: String? = null,
    val merchantHint: String = "",
    val channelLabel: String = ""
)

data class TransactionSourceLink(
    val transactionId: String,
    val sourceId: String,
    val linkRole: SourceLinkRole,
    val candidateId: String? = null
)

data class TransactionChange(
    val opId: String = UUID.randomUUID().toString(),
    val opType: ChangeOpType,
    val affectedTxIds: List<String>,
    val beforeRevisions: Map<String, Int>,
    val afterRevisions: Map<String, Int>,
    val inversePayloadJson: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class UserMerchantRule(
    val id: String = UUID.randomUUID().toString(),
    val sourceScope: String = "*",
    val normalizedMerchant: String,
    val category: String,
    val ruleVersion: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class UserProfile(
    val realName: String = "",
    val aliases: List<String> = emptyList(),
    val ownCardTails: List<String> = emptyList(),
    val brokers: List<String> = listOf(
        "华泰证券", "东方财富", "中信证券", "招商证券", "国泰君安", "广发证券", "同花顺", "海通证券", "银河证券"
    )
)

data class AiConfig(
    val mode: String = MODE_LOCAL, // MODE_LOCAL (offline zero-key default) | MODE_OPENAI_COMPATIBLE | MODE_MOCK
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val timeoutMs: Int = 15000
) {
    val isRealApiEnabled: Boolean
        get() = mode == MODE_OPENAI_COMPATIBLE && apiKey.isNotBlank() && baseUrl.isNotBlank()

    val isMissingApiKey: Boolean
        get() = mode == MODE_OPENAI_COMPATIBLE && apiKey.isBlank()

    val displayLabel: String
        get() = when {
            isRealApiEnabled -> "AI 接口 ($model) 已就绪"
            isMissingApiKey -> "未配置 API Key（点击右上角设置）"
            else -> "纯离线本地引擎"
        }

    companion object {
        const val MODE_LOCAL = "local"
        const val MODE_MOCK = "mock"
        const val MODE_OPENAI_COMPATIBLE = "openai_compatible"
    }
}

data class RawNotification(
    val appPackage: String,
    val appName: String,
    val title: String,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val sourceType: String = "notification",
    val isGroupSummary: Boolean = false,
    val subMessages: List<String> = emptyList(),
    val notificationId: String = UUID.randomUUID().toString(),
    val fromSummary: Boolean = false,
    val externalId: String? = null
)

data class ParsedClue(
    val raw: RawNotification,
    val amountMinor: Long?,
    val currency: String = "CNY",
    val scale: Int = 2,
    val candidates: List<AmountCandidate> = emptyList(),
    val primaryCandidateId: String? = null,
    val feeCandidateId: String? = null,
    val splitPartIds: List<String> = emptyList(),
    val settlementCandidateId: String? = null,
    val ambiguityReason: String? = null,
    val roughDirection: String, // OUT | IN | REFUND | OFFSET | ZERO_AUTH | UNKNOWN
    val priority: Int,
    val cardTail: String?,
    val isZeroAuth: Boolean,
    val preInternalReason: String?,
    val sourceRecord: TransactionSource
) {
    val amount: Double?
        get() = amountMinor?.let { MoneyMath.minorToDouble(it, scale) }
}

data class BufferedGroup(
    val groupId: String,
    val createdAtMs: Long,
    var expiresAtMs: Long,
    val amountMinor: Long?,
    val currency: String = "CNY",
    val scale: Int = 2,
    var roughDirection: String,
    val clues: MutableList<ParsedClue> = mutableListOf()
) {
    val amount: Double?
        get() = amountMinor?.let { MoneyMath.minorToDouble(it, scale) }
}

data class MergedPayload(
    val groupId: String,
    val createdAtMs: Long,
    val observedAtMs: Long = createdAtMs,
    val amountMinor: Long?,
    val currency: String = "CNY",
    val scale: Int = 2,
    val candidates: List<AmountCandidate> = emptyList(),
    val primaryCandidateId: String? = null,
    val feeCandidateId: String? = null,
    val splitParts: List<AmountCandidate> = emptyList(),
    val settlementCandidate: AmountCandidate? = null,
    val ambiguityReason: String? = null,
    val roughDirection: String,
    val paymentMethod: String,
    val sourceChannels: List<String>,
    val mergedNotifications: Int,
    val sanitizedText: String,
    val redactionNotes: List<String>,
    val preInternalReason: String?,
    val isZeroAuth: Boolean,
    val deepLink: String,
    val deepLinkLabel: String,
    val sources: List<TransactionSource> = emptyList()
) {
    val amount: Double?
        get() = amountMinor?.let { MoneyMath.minorToDouble(it, scale) }
}

data class Transaction(
    val id: String,
    val occurredAtMs: Long,
    val observedAtMs: Long = occurredAtMs,
    val updatedAtMs: Long = occurredAtMs,
    val revision: Int = 1,
    val direction: TxDirection,
    val amountMinor: Long,
    val currency: String = "CNY",
    val scale: Int = 2,
    val settlementAmountMinor: Long? = null,
    val settlementCurrency: String? = null,
    val settlementScale: Int = 2,
    val settlementIsEstimated: Boolean = false,
    val merchant: String,
    val itemSummary: String,
    val category: String,
    val paymentMethod: String,
    val sourceChannels: List<String>,
    val mergedNotifications: Int,
    val status: TxStatus,
    val ignoreReason: String = "",
    val deepLink: String = "",
    val deepLinkLabel: String = "",
    val sanitizedText: String = "",
    val aiModeUsed: String = "local",
    val aiReasoning: String = "",
    val relatedTxId: String? = null,
    val rescuedByUser: Boolean = false,
    val legacyMergedCount: Int = 0,
    val legacyMissingSources: Boolean = false
) {
    init {
        require(amountMinor >= 0L) { "Transaction amountMinor must be non-negative" }
        require(revision >= 1) { "Transaction revision must be >= 1" }
    }

    val createdAtMs: Long
        get() = occurredAtMs

    val amount: Double
        get() = MoneyMath.minorToDouble(amountMinor, scale)
}

data class OfflineQueueItem(
    val queueId: String,
    val enqueuedAtMs: Long,
    val payload: MergedPayload,
    val failures: Int = 0
)

data class AuditLogEntry(
    val id: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val stage: String,
    val message: String,
    val sanitizedSample: String = ""
)

data class CurrencyTotals(
    val currency: String,
    val scale: Int,
    val grossExpenseMinor: Long,
    val totalRefundMinor: Long,
    val totalOffsetMinor: Long,
    val netPersonalExpenseMinor: Long,
    val totalIncomeMinor: Long,
    val actualCashInflowMinor: Long,
    val netBalanceMinor: Long,
    val recordedCount: Int,
    val needsReviewCount: Int,
    val ignoredCount: Int
)

data class LedgerSummary(
    val grossExpense: Double,
    val totalRefund: Double,
    val totalOffset: Double = 0.0,
    val netExpense: Double,
    val totalIncome: Double,
    val netBalance: Double,
    val recordedCount: Int,
    val needsReviewCount: Int = 0,
    val ignoredCount: Int,
    val offlineQueueCount: Int,
    val dbSizeBytes: Long,
    val byCurrency: Map<String, CurrencyTotals> = emptyMap()
)

data class CurrencyCheckTotal(
    val currency: String,
    val scale: Int,
    val txCount: Int,
    val totalAmountMinor: Long
)

data class BackupSnapshot(
    val schemaVersion: Int = 3,
    val exportedAtMs: Long,
    val profile: UserProfile,
    val userRules: List<UserMerchantRule>,
    val transactions: List<Transaction>,
    val sources: List<TransactionSource>,
    val sourceLinks: List<TransactionSourceLink>,
    val changes: List<TransactionChange>,
    val pendingQueue: List<OfflineQueueItem>,
    val currencyChecks: List<CurrencyCheckTotal>
)

data class RestoreReport(
    val transactionCount: Int,
    val sourceCount: Int,
    val linkCount: Int,
    val changeCount: Int,
    val pendingQueueCount: Int,
    val currencyChecks: List<CurrencyCheckTotal>
)

data class MigrationReport(
    val fromVersion: Int,
    val toVersion: Int,
    val migratedRows: Int,
    val anomalousRows: Int,
    val legacyMissingSourceRows: Int,
    val notes: List<String>
)

internal fun effectiveKey(typed: String, endpoint: String, bakedEndpoint: String, baked: String): String =
    typed.ifBlank { if (endpoint.trimEnd('/') == bakedEndpoint.trimEnd('/')) baked else "" }
