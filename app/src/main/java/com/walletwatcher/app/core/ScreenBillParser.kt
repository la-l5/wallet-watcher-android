package com.walletwatcher.app.core

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

enum class ScreenPageKind {
    PAY_SUCCESS,
    RECEIVE_SUCCESS,
    BILL_DETAIL
}

enum class ScreenIngestOutcome {
    MERGED_INTO_BUFFER,
    BACKFILLED_COMMITTED,
    ENQUEUED_NEW,
    ALREADY_EXISTS,
    SKIPPED_NO_MATCH,
    SKIPPED_AMBIGUOUS,
    DEFERRED_RESTORE,
    IGNORED
}

data class ScreenBillCapture(
    val appPackage: String,
    val appName: String,
    val pageKind: ScreenPageKind,
    val directionHint: String, // OUT | IN | REFUND
    val amountMinor: Long,
    val currency: String = "CNY",
    val scale: Int = 2,
    val merchant: String,
    val paymentMethod: String = "",
    val actionVerb: String = "",
    val itemDescription: String = "", // Local-only product label ("商品"/"商品说明"); excluded from cloud AI text
    val structuredNoteTag: String = "",
    val internalHint: String = "",
    val statusText: String = "",
    val eventTimeMs: Long? = null,
    val observedAtMs: Long,
    val externalId: String? = null, // Only populated from genuine platform order/transaction IDs
    val receiptDedupKey: String? = null, // Second-precision (transferTime+receiveTime+amount) guard for WeChat receipts
    val screenFingerprint: String,
    val hasAmbiguousAmounts: Boolean = false
) {
    /**
     * Converts to a minimal structured RawNotification:
     * - content carries ONLY structured fields (safe for optional cloud AI after PrivacySanitizer).
     * - itemDescription is routed separately via localItemHint (local ledger summary & offline category only).
     * - Raw free-text transfer remarks/notes are never forwarded.
     */
    fun toRawNotification(): RawNotification {
        val formatted = MoneyMath.formatUnsignedMinor(amountMinor, currency, scale)
        val effectiveVerb = actionVerb.ifBlank {
            when (pageKind) {
                ScreenPageKind.PAY_SUCCESS -> "支付成功"
                ScreenPageKind.RECEIVE_SUCCESS -> "收款成功"
                ScreenPageKind.BILL_DETAIL -> when (directionHint) {
                    "REFUND" -> "退款成功"
                    "IN" -> "收款到账"
                    else -> "支付成功"
                }
            }
        }
        val title = "${appName}$effectiveVerb"
        val content = buildString {
            when (pageKind) {
                ScreenPageKind.PAY_SUCCESS -> {
                    when {
                        internalHint.isNotBlank() -> append("$effectiveVerb，$internalHint，$formatted")
                        effectiveVerb.contains("转账") -> append("转账成功，转账$formatted")
                        else -> append("$effectiveVerb，实付$formatted")
                    }
                }
                ScreenPageKind.RECEIVE_SUCCESS -> {
                    if (appPackage == ScreenBillParser.PKG_WECHAT) {
                        append("你已收款，资金已存入零钱，收款$formatted")
                    } else {
                        append("收款成功，到账$formatted")
                    }
                }
                ScreenPageKind.BILL_DETAIL -> {
                    append("账单详情，$effectiveVerb，$formatted")
                }
            }
            if (merchant.isNotBlank() && merchant != "未知商户") {
                append("，商户：$merchant")
            }
            if (paymentMethod.isNotBlank()) {
                append("，付款方式：$paymentMethod")
            }
            if (structuredNoteTag.isNotBlank()) {
                append("，类型：$structuredNoteTag")
            }
            if (statusText.isNotBlank() && pageKind == ScreenPageKind.BILL_DETAIL) {
                append("，当前状态：$statusText")
            }
        }
        return RawNotification(
            appPackage = appPackage,
            appName = appName,
            title = title,
            content = content,
            timestampMs = eventTimeMs ?: observedAtMs,
            sourceType = ScreenBillParser.SOURCE_TYPE_ACCESSIBILITY,
            notificationId = externalId ?: receiptDedupKey ?: screenFingerprint,
            externalId = externalId,
            merchantOverride = merchant.takeIf { it.isNotBlank() && it != "未知商户" },
            ambiguityOverride = if (hasAmbiguousAmounts) "屏幕页面存在多个候选金额且无明确实付标识，已标记待核对" else null,
            localItemHint = itemDescription.takeIf { it.length >= 2 },
            dedupFingerprintOverride = receiptDedupKey
        )
    }
}

/**
 * Lightweight read-only node interface so tree-walking security invariants (package/window validation,
 * password detection, and strict truncation discard) can be thoroughly verified in pure JVM tests
 * as well as in WalletAccessibilityService.
 */
interface AccessibleNode {
    val packageName: String?
    val windowId: Int
    val isPassword: Boolean
    val text: String?
    val contentDescription: String?
    val childCount: Int
    fun getChild(index: Int): AccessibleNode?
    fun recycleSafely() {}
}

data class TreeSnapshotResult(
    val texts: List<String>,
    val hasPasswordField: Boolean,
    val truncated: Boolean,
    val rejectedReason: String? = null
) {
    val isSafeToParse: Boolean
        get() = rejectedReason == null && !hasPasswordField && !truncated && texts.isNotEmpty()
}

object AccessibilitySnapshotCollector {
    const val DEFAULT_BUDGET_NANOS = 50_000_000L // 50ms traversal budget; any overrun discards snapshot
    const val DEFAULT_MAX_NODES = 600
    const val DEFAULT_MAX_TEXT_NODES = 200
    const val DEFAULT_MAX_DEPTH = 36
    const val DEFAULT_MAX_TEXT_LENGTH = 140

    fun collect(
        eventPackage: String,
        eventWindowId: Int,
        root: AccessibleNode?,
        windowClassForRoot: String?,
        nanoTimeProvider: () -> Long = { System.nanoTime() },
        budgetNanos: Long = DEFAULT_BUDGET_NANOS,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxTextNodes: Int = DEFAULT_MAX_TEXT_NODES,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): TreeSnapshotResult {
        val deadlineNanos = nanoTimeProvider() + budgetNanos

        if (eventPackage !in ScreenBillParser.ALLOWED_PACKAGES) {
            return TreeSnapshotResult(emptyList(), hasPasswordField = false, truncated = false, rejectedReason = "disallowed_event_package")
        }
        if (root == null) {
            return TreeSnapshotResult(emptyList(), hasPasswordField = false, truncated = false, rejectedReason = "null_root")
        }
        val rootPkg = root.packageName
        if (rootPkg.isNullOrBlank() || rootPkg != eventPackage || rootPkg !in ScreenBillParser.ALLOWED_PACKAGES) {
            return TreeSnapshotResult(emptyList(), hasPasswordField = false, truncated = false, rejectedReason = "root_package_mismatch")
        }
        val rootWinId = root.windowId
        if (eventWindowId != -1 && (rootWinId == -1 || rootWinId != eventWindowId)) {
            return TreeSnapshotResult(emptyList(), hasPasswordField = false, truncated = false, rejectedReason = "window_id_mismatch")
        }
        if (ScreenBillParser.isBlockedWindowClass(windowClassForRoot)) {
            return TreeSnapshotResult(emptyList(), hasPasswordField = false, truncated = false, rejectedReason = "blocked_window_class")
        }

        val texts = ArrayList<String>(48)
        var hasPassword = false
        var truncated = false
        var childReadError = false
        var visitedNodes = 0

        fun traverse(node: AccessibleNode, depth: Int) {
            if (hasPassword || truncated || childReadError) return
            if (nanoTimeProvider() > deadlineNanos) {
                truncated = true
                return
            }
            if (depth > maxDepth) {
                truncated = true
                return
            }
            if (visitedNodes >= maxNodes) {
                truncated = true
                return
            }

            visitedNodes++
            if (node.isPassword) {
                hasPassword = true
                return
            }

            val rawText = node.text?.trim().orEmpty()
            val candidateText = if (rawText.isNotEmpty()) {
                rawText
            } else {
                node.contentDescription?.trim().orEmpty()
            }
            if (candidateText.isNotEmpty() && candidateText.length <= DEFAULT_MAX_TEXT_LENGTH) {
                if (texts.size >= maxTextNodes) {
                    truncated = true
                    return
                }
                texts.add(candidateText)
            }

            val count = node.childCount
            for (i in 0 until count) {
                if (hasPassword || truncated || childReadError) break
                if (nanoTimeProvider() > deadlineNanos) {
                    truncated = true
                    break
                }
                // Note: In Android AccessibilityNodeInfo, getChild(i) legitimately returns null for
                // views not important for accessibility or offscreen RecyclerView items; we skip nulls
                // cleanly, while any thrown exception indicates an IPC/snapshot error and rejects the tree.
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    childReadError = true
                    break
                } ?: continue
                try {
                    traverse(child, depth + 1)
                } finally {
                    child.recycleSafely()
                }
            }
        }

        traverse(root, 0)
        if (!truncated && !childReadError && nanoTimeProvider() > deadlineNanos) {
            truncated = true
        }
        val incomplete = hasPassword || truncated || childReadError
        return TreeSnapshotResult(
            texts = if (incomplete) emptyList() else texts,
            hasPasswordField = hasPassword,
            truncated = truncated || childReadError,
            rejectedReason = when {
                hasPassword -> "password_field"
                childReadError -> "child_read_error"
                truncated -> "budget_truncated"
                else -> null
            }
        )
    }
}

/**
 * Pure Kotlin read-only parser for WeChat and Alipay payment result, receipt, and bill detail screens.
 * Strict privacy guard:
 * - Only accepts com.tencent.mm and com.eg.android.AlipayGphone.
 * - Immediately rejects password screens, keyboards, login/auth dialogs, and chat screens.
 * - Extracts ONLY structured fields; never forwards raw free-text notes/remarks to ledger or AI.
 * - Only assigns externalId from real platform transaction/order IDs (never synthetic time+amount hashes).
 */
object ScreenBillParser {

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    const val SOURCE_TYPE_ACCESSIBILITY = "accessibility_page"

    val ALLOWED_PACKAGES: Set<String> = setOf(PKG_WECHAT, PKG_ALIPAY)

    private val BLOCKED_CLASS_HINTS = listOf(
        "pwd", "password", "keyboard", "biometric", "fingerprint",
        "faceverify", "login", "chattingui", "launcherui", "paypwd", "checkpwd"
    )

    private val PASSWORD_OR_AUTH_TEXTS = listOf(
        "请输入支付密码", "输入支付密码", "输入密码", "忘记密码", "安全键盘",
        "验证指纹", "指纹支付", "面容支付", "刷脸支付", "确认支付", "立即付款", "验证支付密码"
    )

    private val CHAT_SCREEN_CUES = listOf(
        "按住说话", "切换到按住说话", "切换到语音", "更多功能按钮", "输入消息", "发送语音"
    )

    private val FAILED_OR_PENDING_STATUS = listOf(
        "支付失败", "付款失败", "交易失败", "交易关闭", "已关闭", "已取消", "未支付", "待支付", "待确认收款", "已退还"
    )

    private val NON_PRIMARY_AMOUNT_PREFIXES = listOf(
        "优惠", "立减", "满减", "抵扣", "减免", "原价", "标价", "商品总额",
        "红包", "余额", "可用余额", "可用额度", "服务费", "手续费", "积分", "金币", "省"
    )

    private val INTERNAL_SEMANTIC_CUES = listOf(
        "零钱提现", "余额提现", "零钱充值", "余额充值", "零钱通转入", "零钱通转出",
        "余额宝转入", "余额宝转出", "信用卡还款", "花呗还款", "白条还款", "银证转账"
    )

    private val SAFE_NOTE_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "AA返还" to listOf("AA返还", "AA收款", "分摊返还", "垫付返还"),
        "费用报销" to listOf("报销", "差旅报销"),
        "餐饮" to listOf("咖啡", "奶茶", "午饭", "晚饭", "早饭", "外卖", "聚餐"),
        "房租水电" to listOf("房租", "水费", "电费", "燃气", "物业", "宽带")
    )

    private val MERCHANT_BOILERPLATE = setOf(
        "支付成功", "付款成功", "转账成功", "交易成功", "收款成功", "已收款", "已收钱", "你已收钱",
        "你已收款，资金已存入零钱", "已收款，资金已存入零钱", "已存入零钱，可直接消费",
        "账单详情", "完成", "返回", "关闭", "返回商家", "查看详情", "查看零钱", "查看账单",
        "微信支付", "微信收款", "支付宝", "支付宝收款", "付款方式", "支付方式", "当前状态",
        "商品", "商品说明", "商户全称", "收款方", "收款方全称", "交易对方", "转账说明",
        "支付时间", "付款时间", "收款时间", "转账时间", "创建时间", "退款时间",
        "交易单号", "商户单号", "转账单号", "订单号", "商家订单号", "账单分类", "账单服务",
        "对订单有疑惑", "常见问题", "联系商户", "定位到聊天位置", "查看往来记录",
        "摇一摇", "去看看", "去使用", "立即领取", "评价", "展开", "收起", "分享", "全部账单",
        "零钱", "微信零钱", "零钱通", "余额", "支付宝余额", "余额宝", "花呗", "信用卡", "储蓄卡"
    )

    private val FIELD_LABEL_ONLY_TOKENS = setOf(
        "付款方式", "支付方式", "扣款方式", "收款方式", "当前状态", "交易状态",
        "商品", "商品说明", "商户全称", "收款方", "收款方全称", "交易对方", "转账说明",
        "支付时间", "付款时间", "收款时间", "转账时间", "创建时间", "退款时间",
        "交易单号", "商户单号", "转账单号", "订单号", "商家订单号", "账单分类", "账单服务",
        "完成", "返回", "关闭", "返回商家", "查看详情", "查看零钱", "查看账单"
    )

    private val FREE_TEXT_FIELD_LABELS = setOf(
        "商品", "商品说明", "转账说明", "备注", "收款理由", "说明"
    )

    private val NON_MERCHANT_FIELD_LABELS = setOf(
        "转账说明", "备注", "收款理由", "说明", "商品", "商品说明",
        "付款方式", "支付方式", "扣款方式", "收款方式", "当前状态", "交易状态",
        "支付时间", "付款时间", "收款时间", "转账时间", "创建时间", "退款时间", "交易时间",
        "交易单号", "商户单号", "转账单号", "订单号", "商家订单号",
        "优惠", "立减", "满减", "原价", "实付", "账单分类"
    )

    private val COUNTERPARTY_PREFIX_REGEX = Regex(
        """^(?:扫二维码付款[-—\s]*给|二维码收款[-—\s]*来自|群收款[-—\s]*来自|群收款[-—\s]*给|转账[-—\s]*来自|转账[-—\s]*转给|转账给|向|付款给|收款方[：:\s]*)"""
    )

    private val RED_PACKET_OWNER_REGEX = Regex("""^(.{1,20}?)的红包$""")
    private val STANDALONE_CURRENCY_REGEX = Regex("""^[+-]?\s*[¥￥$€£]$""")
    private val BARE_AMOUNT_REGEX = Regex("""^([+-])?\s*(\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?)\s*(元)?$""")
    private val INLINE_AMOUNT_REGEX = Regex("""([+-])?\s*(?:[¥￥]|RMB|CNY)\s*([+-])?\s*(\d{1,7}(?:,\d{3})*(?:\.\d{1,2})?)\s*(元)?|([+-])?\s*(\d{1,7}(?:,\d{3})*\.\d{2})\s*(元)?""")
    private val DATE_TIME_REGEX = Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    private data class ExtractedAmount(
        val index: Int,
        val amountMinor: Long,
        val sign: Char?, // '-' | '+' | null
        val isExplicitPaid: Boolean
    )

    private data class PrimaryAmountChoice(
        val amount: ExtractedAmount,
        val hasAmbiguousAlternatives: Boolean
    )

    fun isBlockedWindowClass(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val lower = className.lowercase(Locale.US)
        return BLOCKED_CLASS_HINTS.any { lower.contains(it) }
    }

    fun parse(
        packageName: String,
        className: String?,
        rawTexts: List<String>,
        hasPasswordField: Boolean = false,
        observedAtMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ScreenBillCapture? {
        if (packageName !in ALLOWED_PACKAGES) return null
        if (hasPasswordField) return null
        if (isBlockedWindowClass(className)) return null

        val texts = rawTexts.asSequence()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() && it.length <= 140 }
            .toList()
        if (texts.isEmpty()) return null

        // Hard privacy guard: never touch password prompts or normal chat screens
        if (texts.any { t -> PASSWORD_OR_AUTH_TEXTS.any { t.contains(it) } }) return null
        if (texts.any { t -> CHAT_SCREEN_CUES.any { t.contains(it) } }) return null

        return when (packageName) {
            PKG_WECHAT -> parseWeChat(texts, observedAtMs, zoneId)
            PKG_ALIPAY -> parseAlipay(texts, observedAtMs, zoneId)
            else -> null
        }
    }

    private fun parseWeChat(
        texts: List<String>,
        observedAtMs: Long,
        zoneId: ZoneId
    ): ScreenBillCapture? {
        val explicitBillDetailHeader = texts.any {
            it == "账单详情" || it == "当前状态" || it == "账单服务"
        }
        val hasOrderIdLabel = texts.any {
            it == "交易单号" || it == "商户单号" || it == "转账单号"
        }
        val billLabelHits = countExactOrPrefixLabels(
            texts,
            listOf("当前状态", "支付时间", "收款时间", "转账时间", "支付方式", "商户全称", "交易单号", "商户单号", "转账单号", "账单服务")
        )
        val hasReceiveAnchor = texts.any { t ->
            t.contains("你已收款") || t.contains("已收款，资金已存入零钱") ||
                t.contains("已存入零钱，可直接消费") || t == "已收钱" || t == "你已收钱" || t == "收款成功"
        }
        val livePayVerb = texts.firstNotNullOfOrNull { t ->
            when {
                t == "转账成功" || t.startsWith("转账成功") -> "转账成功"
                t == "付款成功" || t.startsWith("付款成功") -> "付款成功"
                t == "支付成功" || t.startsWith("支付成功") -> "支付成功"
                else -> null
            }
        }

        // Only treat as BILL_DETAIL when genuine bill-detail structure exists (avoid misclassifying live result pages
        // that merely show "支付方式" / "支付时间" / "交易单号" alongside a live "支付成功" header).
        val isBillDetail = when {
            explicitBillDetailHeader -> true
            hasOrderIdLabel && livePayVerb == null && !hasReceiveAnchor -> true
            billLabelHits >= 3 && livePayVerb == null && !hasReceiveAnchor -> true
            else -> false
        }
        if (isBillDetail) {
            return parseBillDetail(PKG_WECHAT, "微信", texts, observedAtMs, zoneId)
        }

        if (hasReceiveAnchor) {
            return parseReceiveSuccess(PKG_WECHAT, "微信", texts, observedAtMs, zoneId)
        }

        if (livePayVerb != null) {
            return parsePaySuccess(PKG_WECHAT, "微信", livePayVerb, texts, observedAtMs, zoneId)
        }

        return null
    }

    private fun parseAlipay(
        texts: List<String>,
        observedAtMs: Long,
        zoneId: ZoneId
    ): ScreenBillCapture? {
        val explicitBillDetailHeader = texts.any {
            it == "账单详情" || it == "交易状态" || it == "账单分类"
        }
        val hasOrderIdLabel = texts.any {
            it == "订单号" || it == "商家订单号"
        }
        val billLabelHits = countExactOrPrefixLabels(
            texts,
            listOf("付款方式", "商品说明", "收款方全称", "创建时间", "订单号", "商家订单号", "账单分类", "交易状态", "对方账户")
        )
        val hasReceiveAnchor = texts.any { t ->
            t == "收款成功" || t == "收钱到账" || t == "已收款" || t.startsWith("收款成功")
        }
        val livePayVerb = texts.firstNotNullOfOrNull { t ->
            when {
                t == "转账成功" || t.startsWith("转账成功") -> "转账成功"
                t == "付款成功" || t.startsWith("付款成功") -> "付款成功"
                t == "支付成功" || t.startsWith("支付成功") -> "支付成功"
                t == "交易成功" -> "交易成功"
                else -> null
            }
        }

        val isBillDetail = when {
            explicitBillDetailHeader -> true
            hasOrderIdLabel && livePayVerb == null && !hasReceiveAnchor -> true
            billLabelHits >= 3 && livePayVerb == null && !hasReceiveAnchor -> true
            else -> false
        }
        if (isBillDetail) {
            return parseBillDetail(PKG_ALIPAY, "支付宝", texts, observedAtMs, zoneId)
        }

        if (hasReceiveAnchor) {
            return parseReceiveSuccess(PKG_ALIPAY, "支付宝", texts, observedAtMs, zoneId)
        }

        if (livePayVerb != null) {
            return parsePaySuccess(PKG_ALIPAY, "支付宝", livePayVerb, texts, observedAtMs, zoneId)
        }

        return null
    }

    private fun parsePaySuccess(
        pkg: String,
        appName: String,
        actionVerb: String,
        texts: List<String>,
        observedAtMs: Long,
        zoneId: ZoneId
    ): ScreenBillCapture? {
        if (texts.any { t -> FAILED_OR_PENDING_STATUS.any { t.contains(it) } }) return null

        val choice = findPrimaryAmount(texts, preferSigned = false) ?: return null
        val amount = choice.amount
        val paymentMethod = findFieldValue(texts, listOf("付款方式", "支付方式", "扣款方式"))
            ?: texts.firstOrNull { isPaymentMethodLine(it) }.orEmpty()
        val itemDesc = findFieldValue(texts, listOf("商品", "商品说明"))?.take(32).orEmpty()
        val rawNote = findFieldValue(texts, listOf("备注", "转账说明")).orEmpty()
        val nonMerchantIndices = collectNonMerchantIndices(texts)
        val excludedFreeTexts = setOf(itemDesc, rawNote, rawNote.take(32)).filter { it.isNotBlank() }.toSet()
        val merchant = extractMerchantForResultPage(texts, amount.index, paymentMethod, nonMerchantIndices, excludedFreeTexts)
        val safeNoteTag = classifySafeNoteTag(rawNote.ifBlank { itemDesc })
        val internalHint = detectInternalHint(texts)
        val eventTimeMs = extractEventTime(texts, listOf("支付时间", "付款时间", "转账时间", "创建时间"), zoneId)
        val orderNo = findOrderId(texts)
        val externalId = orderNo?.let { hashOrderId(pkg, it) }
        val fp = buildFingerprint(pkg, ScreenPageKind.PAY_SUCCESS, "OUT", amount.amountMinor, merchant, eventTimeMs ?: 0L)

        return ScreenBillCapture(
            appPackage = pkg,
            appName = appName,
            pageKind = ScreenPageKind.PAY_SUCCESS,
            directionHint = "OUT",
            amountMinor = amount.amountMinor,
            merchant = merchant,
            paymentMethod = paymentMethod,
            actionVerb = actionVerb,
            itemDescription = itemDesc,
            structuredNoteTag = safeNoteTag,
            internalHint = internalHint,
            statusText = actionVerb,
            eventTimeMs = eventTimeMs,
            observedAtMs = observedAtMs,
            externalId = externalId,
            screenFingerprint = fp,
            hasAmbiguousAmounts = choice.hasAmbiguousAlternatives
        )
    }

    private fun parseReceiveSuccess(
        pkg: String,
        appName: String,
        texts: List<String>,
        observedAtMs: Long,
        zoneId: ZoneId
    ): ScreenBillCapture? {
        if (texts.any { t -> FAILED_OR_PENDING_STATUS.any { t.contains(it) } }) return null
        if (texts.any { it.contains("零钱提现") || it.contains("余额提现") || it.contains("零钱充值") || it.contains("余额充值") }) {
            return null
        }

        val choice = findPrimaryAmount(texts, preferSigned = false) ?: return null
        val amount = choice.amount
        val rawNote = findFieldValue(texts, listOf("转账说明", "备注", "收款理由", "说明")).orEmpty()
        val safeNoteTag = classifySafeNoteTag(rawNote)
        val transferTimeRaw = findFieldValue(texts, listOf("转账时间"))
        val receiveTimeRaw = findFieldValue(texts, listOf("收款时间", "到账时间"))
        val transferTimeSecMs = transferTimeRaw?.let { parseDateTimeWithSecondsMs(it, zoneId) }
        val receiveTimeSecMs = receiveTimeRaw?.let { parseDateTimeWithSecondsMs(it, zoneId) }
        val eventTimeMs = receiveTimeSecMs
            ?: transferTimeSecMs
            ?: extractEventTime(texts, listOf("收款时间", "转账时间", "到账时间", "创建时间"), zoneId)
        val orderNo = findOrderId(texts)

        var merchant = findFieldValue(texts, listOf("付款方", "转账人", "对方", "发红包人", "来自"))
            ?.let { cleanCounterparty(it) }
            .orEmpty()
        if (merchant.isBlank()) {
            val rp = texts.firstNotNullOfOrNull { RED_PACKET_OWNER_REGEX.matchEntire(it)?.groupValues?.getOrNull(1) }
            if (!rp.isNullOrBlank()) {
                merchant = cleanCounterparty(rp)
            }
        }
        val nonMerchantIndices = collectNonMerchantIndices(texts)
        val excludedFreeTexts = setOf(rawNote).filter { it.isNotBlank() }.toSet()
        if (merchant.isBlank()) {
            merchant = extractMerchantForResultPage(texts, amount.index, "", nonMerchantIndices, excludedFreeTexts)
        }
        if (merchant == "未知商户") {
            merchant = if (pkg == PKG_WECHAT) "微信收款" else "支付宝收款"
        }

        val paymentMethod = if (pkg == PKG_WECHAT) "微信零钱" else "支付宝余额"
        // Genuine order ID goes to externalId; dual second-precision (transferTime + receiveTime + amount + payer)
        // goes to receiptDedupKey so re-opening the same WeChat group bubble never double-books while
        // distinct known payers never collide.
        val externalId = orderNo?.let { hashOrderId(pkg, it) }
        val receiptDedupKey = if (externalId == null && transferTimeSecMs != null && receiveTimeSecMs != null) {
            hashReceiptKey(pkg, transferTimeSecMs, receiveTimeSecMs, amount.amountMinor, merchant)
        } else {
            null
        }
        val fp = buildFingerprint(pkg, ScreenPageKind.RECEIVE_SUCCESS, "IN", amount.amountMinor, merchant, eventTimeMs ?: 0L)

        return ScreenBillCapture(
            appPackage = pkg,
            appName = appName,
            pageKind = ScreenPageKind.RECEIVE_SUCCESS,
            directionHint = "IN",
            amountMinor = amount.amountMinor,
            merchant = merchant,
            paymentMethod = paymentMethod,
            actionVerb = "收款成功",
            structuredNoteTag = safeNoteTag,
            statusText = "收款成功",
            eventTimeMs = eventTimeMs,
            observedAtMs = observedAtMs,
            externalId = externalId,
            receiptDedupKey = receiptDedupKey,
            screenFingerprint = fp,
            hasAmbiguousAmounts = choice.hasAmbiguousAlternatives
        )
    }

    private fun parseBillDetail(
        pkg: String,
        appName: String,
        texts: List<String>,
        observedAtMs: Long,
        zoneId: ZoneId
    ): ScreenBillCapture? {
        val statusVal = findFieldValue(texts, listOf("当前状态", "交易状态"))
            ?: texts.firstOrNull { it in setOf("支付成功", "交易成功", "已存入零钱", "已收钱", "已全额退款", "已部分退款", "退款成功") }
            ?: ""
        if (FAILED_OR_PENDING_STATUS.any { statusVal.contains(it) }) return null

        val choice = findPrimaryAmount(texts, preferSigned = true) ?: return null
        val amount = choice.amount
        val direction = when {
            statusVal.contains("退款") || texts.any { it.startsWith("退款-") || it.contains("已退回") } -> {
                if (amount.sign == '-') "OUT" else "REFUND"
            }
            amount.sign == '-' -> "OUT"
            amount.sign == '+' -> "IN"
            statusVal.contains("已存入") || statusVal.contains("已收") || statusVal.contains("收款") -> "IN"
            else -> "OUT"
        }

        val paymentMethod = findFieldValue(texts, listOf("支付方式", "付款方式", "收款方式")).orEmpty()
        val itemDesc = findFieldValue(texts, listOf("商品", "商品说明"))?.take(32).orEmpty()
        val rawNote = findFieldValue(texts, listOf("转账说明", "备注")).orEmpty()
        val safeNoteTag = classifySafeNoteTag(rawNote.ifBlank { itemDesc })
        val eventTimeMs = extractEventTime(
            texts,
            listOf("支付时间", "付款时间", "收款时间", "转账时间", "创建时间", "退款时间", "交易时间"),
            zoneId,
            allowUnlabeledFallback = false
        )
        val orderNo = findOrderId(texts)

        val nonMerchantIndices = collectNonMerchantIndices(texts)
        val excludedFreeTexts = setOf(itemDesc, rawNote, rawNote.take(32)).filter { it.isNotBlank() }.toSet()
        // Explicit labeled merchant fields take strict priority over proximity header heuristics
        val fieldMerchant = findFieldValue(texts, listOf("商户全称", "收款方全称", "收款方", "交易对方", "对方账户", "付款方"))
            ?.let { cleanCounterparty(it) }
            ?.takeIf { isValidMerchantCandidate(it, paymentMethod) && it !in excludedFreeTexts }
        val headerMerchant = findHeaderMerchantBeforeAmount(texts, amount.index, paymentMethod, nonMerchantIndices, excludedFreeTexts)
        val merchant = when {
            !fieldMerchant.isNullOrBlank() && fieldMerchant != "未知商户" -> fieldMerchant
            !headerMerchant.isNullOrBlank() && headerMerchant != "未知商户" -> headerMerchant
            else -> "未知商户"
        }

        val externalId = orderNo?.let { hashOrderId(pkg, it) }
        val fp = buildFingerprint(pkg, ScreenPageKind.BILL_DETAIL, direction, amount.amountMinor, merchant, eventTimeMs ?: 0L)

        return ScreenBillCapture(
            appPackage = pkg,
            appName = appName,
            pageKind = ScreenPageKind.BILL_DETAIL,
            directionHint = direction,
            amountMinor = amount.amountMinor,
            merchant = merchant,
            paymentMethod = paymentMethod,
            actionVerb = if (direction == "REFUND") "退款成功" else if (direction == "IN") "收款到账" else "支付成功",
            itemDescription = itemDesc,
            structuredNoteTag = safeNoteTag,
            statusText = statusVal,
            eventTimeMs = eventTimeMs,
            observedAtMs = observedAtMs,
            externalId = externalId,
            screenFingerprint = fp,
            hasAmbiguousAmounts = choice.hasAmbiguousAlternatives
        )
    }

    private fun detectInternalHint(texts: List<String>): String {
        for (cue in INTERNAL_SEMANTIC_CUES) {
            if (texts.any { it.contains(cue) }) return cue
        }
        return ""
    }

    private fun classifySafeNoteTag(rawNote: String): String {
        if (rawNote.isBlank()) return ""
        for ((tag, keywords) in SAFE_NOTE_KEYWORDS) {
            if (keywords.any { rawNote.contains(it, ignoreCase = true) }) {
                return tag
            }
        }
        return ""
    }

    private fun countExactOrPrefixLabels(texts: List<String>, labels: List<String>): Int {
        var count = 0
        for (label in labels) {
            if (texts.any { it == label || it.startsWith("$label：") || it.startsWith("$label:") }) {
                count++
            }
        }
        return count
    }

    private fun findPrimaryAmount(texts: List<String>, preferSigned: Boolean): PrimaryAmountChoice? {
        val candidates = mutableListOf<ExtractedAmount>()

        var i = 0
        while (i < texts.size) {
            val cur = texts[i]
            val prev = texts.getOrNull(i - 1).orEmpty()
            val prev2 = texts.getOrNull(i - 2).orEmpty()

            if (isNonPrimaryAmountContext(cur, prev, prev2)) {
                i++
                continue
            }

            val explicitPaid = cur.contains("实付") || cur.contains("实际支付") ||
                prev.trimEnd('：', ':') in setOf("实付", "实际支付", "实付款") ||
                (STANDALONE_CURRENCY_REGEX.matches(prev) && prev2.trimEnd('：', ':') in setOf("实付", "实际支付", "实付款"))

            // Case 1: Split nodes, e.g. ["¥", "19.90"] or ["-", "¥", "19.90"]
            if (STANDALONE_CURRENCY_REGEX.matches(cur) && i + 1 < texts.size) {
                val next = texts[i + 1]
                val bare = BARE_AMOUNT_REGEX.matchEntire(next)
                if (bare != null) {
                    val minor = MoneyMath.parseDecimalToMinor(bare.groupValues[2], 2)
                    if (minor != null && minor > 0L) {
                        val sign = when {
                            cur.startsWith("-") || bare.groupValues[1] == "-" -> '-'
                            cur.startsWith("+") || bare.groupValues[1] == "+" -> '+'
                            else -> null
                        }
                        candidates.add(ExtractedAmount(i, minor, sign, explicitPaid))
                        i += 2
                        continue
                    }
                }
            }

            // Case 2: Bare numeric node followed by "元" or explicit signed/decimal amount
            val bareOnly = BARE_AMOUNT_REGEX.matchEntire(cur)
            if (bareOnly != null) {
                val next = texts.getOrNull(i + 1).orEmpty()
                val hasYuan = bareOnly.groupValues[3].isNotEmpty() || next == "元"
                val hasDecimals = bareOnly.groupValues[2].contains('.')
                val hasExplicitSign = bareOnly.groupValues[1].isNotEmpty()
                if (hasYuan || hasDecimals || hasExplicitSign) {
                    val minor = MoneyMath.parseDecimalToMinor(bareOnly.groupValues[2], 2)
                    if (minor != null && minor > 0L) {
                        val sign = bareOnly.groupValues[1].firstOrNull()
                        candidates.add(ExtractedAmount(i, minor, sign, explicitPaid))
                        i += if (next == "元") 2 else 1
                        continue
                    }
                }
            }

            // Case 3: Inline currency or decimal amount inside a single node
            if (!DATE_TIME_REGEX.containsMatchIn(cur) && !Regex("""\d{11,}""").containsMatchIn(cur)) {
                val m = INLINE_AMOUNT_REGEX.find(cur)
                if (m != null) {
                    val numStr = m.groupValues[3].ifEmpty { m.groupValues[6] }
                    val signStr = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[5] }
                    val minor = MoneyMath.parseDecimalToMinor(numStr, 2)
                    if (minor != null && minor > 0L) {
                        candidates.add(ExtractedAmount(i, minor, signStr.firstOrNull(), explicitPaid))
                    }
                }
            }
            i++
        }

        if (candidates.isEmpty()) return null

        // Priority 1: explicit "实付" / "实际支付"
        candidates.firstOrNull { it.isExplicitPaid }?.let {
            return PrimaryAmountChoice(it, hasAmbiguousAlternatives = false)
        }

        // Priority 2: signed amount on bill detail
        if (preferSigned) {
            val signed = candidates.filter { it.sign != null }
            if (signed.isNotEmpty()) {
                val distinctSigned = signed.map { it.amountMinor }.distinct()
                return PrimaryAmountChoice(signed.first(), hasAmbiguousAlternatives = distinctSigned.size > 1)
            }
        }

        val distinctAmounts = candidates.map { it.amountMinor }.distinct()
        return PrimaryAmountChoice(
            amount = candidates.first(),
            hasAmbiguousAlternatives = distinctAmounts.size > 1
        )
    }

    private fun isNonPrimaryAmountContext(cur: String, prev: String, prev2: String): Boolean {
        if (NON_PRIMARY_AMOUNT_PREFIXES.any { cur.contains(it) } && !cur.contains("实付")) {
            return true
        }
        val prevTrim = prev.trimEnd('：', ':')
        if (prevTrim in NON_PRIMARY_AMOUNT_PREFIXES || NON_PRIMARY_AMOUNT_PREFIXES.any { prevTrim.endsWith(it) && !prevTrim.contains("实付") }) {
            return true
        }
        if (STANDALONE_CURRENCY_REGEX.matches(prev) && NON_PRIMARY_AMOUNT_PREFIXES.any { prev2.trimEnd('：', ':').endsWith(it) }) {
            return true
        }
        return false
    }

    private fun findFieldValue(texts: List<String>, labels: List<String>): String? {
        for (label in labels) {
            for (i in texts.indices) {
                val t = texts[i]
                if (t == label || t == "$label：" || t == "$label:") {
                    val next = texts.getOrNull(i + 1)?.trim()
                    if (!next.isNullOrEmpty() && next !in FIELD_LABEL_ONLY_TOKENS && !STANDALONE_CURRENCY_REGEX.matches(next)) {
                        return next
                    }
                } else if (t.startsWith("$label：") || t.startsWith("$label:")) {
                    val value = t.substring(label.length + 1).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    private fun firstFreeTextLabelIndex(texts: List<String>): Int {
        for (i in texts.indices) {
            val t = texts[i]
            val trimmed = t.trimEnd('：', ':')
            if (trimmed in FREE_TEXT_FIELD_LABELS || isPrefixedByFreeTextLabel(t)) {
                return i
            }
        }
        return Int.MAX_VALUE
    }

    private fun extractEventTime(
        texts: List<String>,
        labels: List<String>,
        zoneId: ZoneId,
        allowUnlabeledFallback: Boolean = true
    ): Long? {
        val labeled = findFieldValue(texts, labels)
        if (labeled != null) {
            parseDateTimeMs(labeled, zoneId)?.let { return it }
        }
        if (!allowUnlabeledFallback) return null
        val freeStart = firstFreeTextLabelIndex(texts)
        for (i in texts.indices) {
            if (i >= freeStart) break
            parseDateTimeMs(texts[i], zoneId)?.let { return it }
        }
        return null
    }

    internal fun parseDateTimeMs(text: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        val m = DATE_TIME_REGEX.find(text) ?: return null
        return runCatching {
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            val hour = m.groupValues[4].toInt()
            val minute = m.groupValues[5].toInt()
            val second = m.groupValues[6].ifEmpty { "0" }.toInt()
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun parseDateTimeWithSecondsMs(text: String, zoneId: ZoneId): Long? {
        val m = DATE_TIME_REGEX.find(text) ?: return null
        if (m.groupValues[6].isEmpty()) return null
        return parseDateTimeMs(text, zoneId)
    }

    private fun hashReceiptKey(
        pkg: String,
        transferTimeSecMs: Long,
        receiveTimeSecMs: Long,
        amountMinor: Long,
        payerHint: String
    ): String {
        val normPayer = payerHint.trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pkg|rcpt|$transferTimeSecMs|$receiveTimeSecMs|$amountMinor|$normPayer".toByteArray(Charsets.UTF_8))
        return "rcpt_" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun findOrderId(texts: List<String>): String? {
        val raw = findFieldValue(texts, listOf("交易单号", "转账单号", "订单号", "商户单号", "商家订单号")) ?: return null
        val cleaned = raw.replace(" ", "")
        return if (cleaned.length >= 10) cleaned else null
    }

    private fun isPrefixedByFreeTextLabel(text: String): Boolean {
        return FREE_TEXT_FIELD_LABELS.any { label ->
            text.startsWith("$label：") || text.startsWith("$label:")
        }
    }

    /**
     * Marks all indices that MUST NEVER be used for heuristic unlabeled merchant guessing:
     * 1. Monotonic privacy barrier: once ANY free-text label ("商品"/"商品说明"/"备注"/"转账说明"/"收款理由"/"说明")
     *    appears at index `freeStart`, ALL indices >= `freeStart` are excluded from heuristic guessing
     *    (even if dates, amounts, or extra nodes appear inside/after the free text). Explicit labeled
     *    merchant fields ("商户全称"/"收款方"/etc.) still work anywhere via findFieldValue().
     * 2. Any other non-merchant field label and its immediate value slot (e.g. "优惠"/"付款方式").
     */
    private fun collectNonMerchantIndices(texts: List<String>): Set<Int> {
        val excluded = HashSet<Int>()
        val freeStart = firstFreeTextLabelIndex(texts)
        if (freeStart < texts.size) {
            for (k in freeStart until texts.size) {
                excluded.add(k)
            }
        }
        for (i in texts.indices) {
            val t = texts[i]
            val trimmedLabel = t.trimEnd('：', ':')
            if (trimmedLabel in NON_MERCHANT_FIELD_LABELS) {
                excluded.add(i)
                if (i + 1 < texts.size) {
                    excluded.add(i + 1)
                }
            } else if (isPrefixedByNonMerchantLabel(t)) {
                excluded.add(i)
            }
        }
        return excluded
    }

    private fun isPrefixedByNonMerchantLabel(text: String): Boolean {
        return NON_MERCHANT_FIELD_LABELS.any { label ->
            text.startsWith("$label：") || text.startsWith("$label:")
        }
    }

    private fun extractMerchantForResultPage(
        texts: List<String>,
        amountIndex: Int,
        paymentMethod: String,
        nonMerchantIndices: Set<Int> = collectNonMerchantIndices(texts),
        excludedFreeTexts: Set<String> = emptySet()
    ): String {
        val labeled = findFieldValue(texts, listOf("收款方", "商户", "收款商户", "商户全称", "交易对方", "对方"))
        if (!labeled.isNullOrBlank()) {
            val cleaned = cleanCounterparty(labeled)
            if (isValidMerchantCandidate(cleaned, paymentMethod) && cleaned !in excludedFreeTexts) return cleaned
        }

        val searchIndices = buildList {
            for (offset in 1..3) {
                if (amountIndex - offset >= 0) add(amountIndex - offset)
            }
            for (offset in 1..3) {
                if (amountIndex + offset < texts.size) add(amountIndex + offset)
            }
        }
        for (idx in searchIndices) {
            if (idx in nonMerchantIndices) continue
            val raw = texts[idx]
            val cleaned = cleanCounterparty(raw)
            if (isValidMerchantCandidate(cleaned, paymentMethod) && cleaned !in excludedFreeTexts) {
                return cleaned
            }
        }
        return "未知商户"
    }

    private fun findHeaderMerchantBeforeAmount(
        texts: List<String>,
        amountIndex: Int,
        paymentMethod: String = "",
        nonMerchantIndices: Set<Int> = collectNonMerchantIndices(texts),
        excludedFreeTexts: Set<String> = emptySet()
    ): String? {
        val start = (amountIndex - 1).coerceAtLeast(0)
        for (i in start downTo maxOf(0, amountIndex - 4)) {
            if (i in nonMerchantIndices) continue
            val cleaned = cleanCounterparty(texts[i])
            if (isValidMerchantCandidate(cleaned, paymentMethod) && cleaned !in excludedFreeTexts) {
                return cleaned
            }
        }
        return null
    }

    private fun cleanCounterparty(raw: String): String {
        var s = raw.trim()
        s = COUNTERPARTY_PREFIX_REGEX.replace(s, "").trim()
        s = s.removeSuffix("付款").removeSuffix("收款").trim()
        return s.take(32)
    }

    private fun isValidMerchantCandidate(candidate: String, paymentMethod: String): Boolean {
        if (candidate.length < 2 || candidate.length > 32) return false
        if (candidate in MERCHANT_BOILERPLATE) return false
        if (candidate in AiClient.GENERIC_GATEWAY_MERCHANTS) return false
        if (isPrefixedByNonMerchantLabel(candidate)) return false
        if (paymentMethod.isNotBlank() && (candidate == paymentMethod || paymentMethod.contains(candidate))) return false
        if (isPaymentMethodLine(candidate)) return false
        if (STANDALONE_CURRENCY_REGEX.matches(candidate)) return false
        if (BARE_AMOUNT_REGEX.matches(candidate)) return false
        if (INLINE_AMOUNT_REGEX.containsMatchIn(candidate)) return false
        if (DATE_TIME_REGEX.containsMatchIn(candidate)) return false
        if (Regex("""^\d+$""").matches(candidate)) return false
        if (NON_PRIMARY_AMOUNT_PREFIXES.any { candidate.startsWith(it) }) return false
        if (listOf("立减金", "优惠券", "代金券", "申领", "摇一摇", "去领取", "点击查看", "广告").any { candidate.contains(it) }) {
            return false
        }
        return true
    }

    private fun isPaymentMethodLine(text: String): Boolean {
        return listOf("储蓄卡", "信用卡", "借记卡", "微信零钱", "零钱通", "支付宝余额", "余额宝", "花呗", "亲属卡", "先用后付")
            .any { text.contains(it) } && text.length <= 24
    }

    private fun hashOrderId(pkg: String, rawOrder: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pkg|$rawOrder".toByteArray(Charsets.UTF_8))
        val hex = digest.take(10).joinToString("") { "%02x".format(it) }
        return "scr_$hex"
    }

    private fun buildFingerprint(
        pkg: String,
        kind: ScreenPageKind,
        dir: String,
        amountMinor: Long,
        merchant: String,
        eventTimeKey: Long
    ): String {
        val raw = "$pkg|${kind.name}|$dir|$amountMinor|$merchant|$eventTimeKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "fp_" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Window-session aware deduplicator:
 * - Suppresses unlimited content-changed redraws while remaining on the SAME open page session
 *   (even when user stays > 45s on a payment result screen).
 * - Immediately accepts a second genuine same-amount payment after leaving and re-entering the
 *   result screen (new window session).
 * - Supports rollback on async persistence failure so transient errors never suppress retries.
 */
class ScreenCaptureDeduplicator(
    private val sameSessionJitterMs: Long = 1_500L,
    private val maxEntries: Int = 128
) {
    private val lock = Any()
    private val seenInSession = LinkedHashMap<String, Long>()
    private val seenByExternalId = LinkedHashMap<String, Long>()
    private val lastSeenContentAt = LinkedHashMap<String, Pair<Long, Long>>() // contentKey -> (sessionId, atMs)

    fun tryAcquire(
        windowSessionId: Long,
        capture: ScreenBillCapture,
        nowMs: Long = capture.observedAtMs
    ): Boolean = synchronized(lock) {
        // When a genuine platform order/transaction ID is present, it is authoritative for deduplication:
        // distinct strong order IDs are NEVER vetoed by a matching content fingerprint or jitter window,
        // and no cache entry is written until acceptance is final.
        val extId = capture.externalId
        if (!extId.isNullOrBlank()) {
            if (extId in seenByExternalId) return@synchronized false
            seenByExternalId[extId] = nowMs
            trimMap(seenByExternalId)
            return@synchronized true
        }

        val contentKey = capture.screenFingerprint
        val sessionKey = "$windowSessionId|$contentKey"
        if (sessionKey in seenInSession) {
            return@synchronized false
        }

        // Guard against duplicate WINDOW_STATE_CHANGED jitter on the exact same screen within 1.5s
        val prev = lastSeenContentAt[contentKey]
        if (prev != null && prev.first != windowSessionId && kotlin.math.abs(nowMs - prev.second) < sameSessionJitterMs) {
            return@synchronized false
        }

        seenInSession[sessionKey] = nowMs
        lastSeenContentAt[contentKey] = windowSessionId to nowMs
        trimMap(seenInSession)
        trimMap(lastSeenContentAt)
        true
    }

    fun releaseOnFailure(windowSessionId: Long, capture: ScreenBillCapture) = synchronized(lock) {
        val extId = capture.externalId
        if (!extId.isNullOrBlank()) {
            seenByExternalId.remove(extId)
            return@synchronized
        }
        seenInSession.remove("$windowSessionId|${capture.screenFingerprint}")
        val prev = lastSeenContentAt[capture.screenFingerprint]
        if (prev != null && prev.first == windowSessionId) {
            lastSeenContentAt.remove(capture.screenFingerprint)
        }
        Unit
    }

    fun clear() = synchronized(lock) {
        seenInSession.clear()
        seenByExternalId.clear()
        lastSeenContentAt.clear()
    }

    private fun <K, V> trimMap(map: LinkedHashMap<K, V>) {
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }
}
