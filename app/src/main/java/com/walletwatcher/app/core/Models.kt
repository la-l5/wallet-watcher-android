package com.walletwatcher.app.core

import java.util.UUID

enum class TxDirection {
    EXPENSE,
    INCOME,
    REFUND,
    INTERNAL_TRANSFER,
    IGNORED
}

enum class TxStatus {
    RECORDED,
    IGNORED,
    NEEDS_REVIEW
}

data class UserProfile(
    val realName: String = "",
    val aliases: List<String> = emptyList(),
    val ownCardTails: List<String> = emptyList(),
    val brokers: List<String> = listOf(
        "华泰证券", "东方财富", "中信证券", "招商证券", "国泰君安", "广发证券", "同花顺", "海通证券", "银河证券"
    )
)

data class AiConfig(
    val mode: String = MODE_MOCK, // MODE_MOCK | MODE_OPENAI_COMPATIBLE
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val timeoutMs: Int = 15000
) {
    val isRealApiEnabled: Boolean
        get() = mode == MODE_OPENAI_COMPATIBLE && apiKey.isNotBlank() && baseUrl.isNotBlank()

    val displayLabel: String
        get() = if (isRealApiEnabled) "真实 API ($model)" else "内置模拟 AI（默认已就绪）"

    companion object {
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
    val notificationId: String = UUID.randomUUID().toString().take(10)
)

data class ParsedClue(
    val raw: RawNotification,
    val amount: Double?,
    val roughDirection: String, // OUT | IN | REFUND | ZERO_AUTH | UNKNOWN
    val priority: Int,
    val cardTail: String?,
    val isZeroAuth: Boolean,
    val preInternalReason: String?
)

data class BufferedGroup(
    val groupId: String,
    val createdAtMs: Long,
    var expiresAtMs: Long,
    val amount: Double?,
    val roughDirection: String,
    val clues: MutableList<ParsedClue> = mutableListOf()
)

data class MergedPayload(
    val groupId: String,
    val createdAtMs: Long,
    val amount: Double?,
    val roughDirection: String,
    val paymentMethod: String,
    val sourceChannels: List<String>,
    val mergedNotifications: Int,
    val sanitizedText: String,
    val redactionNotes: List<String>,
    val preInternalReason: String?,
    val isZeroAuth: Boolean,
    val deepLink: String,
    val deepLinkLabel: String
)

data class Transaction(
    val id: String,
    val createdAtMs: Long,
    val direction: TxDirection,
    val amount: Double,
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
    val aiModeUsed: String = "mock",
    val aiReasoning: String = "",
    val relatedTxId: String? = null,
    val rescuedByUser: Boolean = false
)

data class OfflineQueueItem(
    val queueId: String,
    val enqueuedAtMs: Long,
    val payload: MergedPayload
)

data class AuditLogEntry(
    val id: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val stage: String,
    val message: String,
    val sanitizedSample: String = ""
)

data class LedgerSummary(
    val grossExpense: Double,
    val totalRefund: Double,
    val netExpense: Double,
    val totalIncome: Double,
    val netBalance: Double,
    val recordedCount: Int,
    val ignoredCount: Int,
    val offlineQueueCount: Int,
    val dbSizeBytes: Long
)
