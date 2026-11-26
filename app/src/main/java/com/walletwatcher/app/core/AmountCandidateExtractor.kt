package com.walletwatcher.app.core

import java.util.Locale

data class CandidateExtractionResult(
    val candidates: List<AmountCandidate>,
    val primaryCandidate: AmountCandidate?,
    val feeCandidate: AmountCandidate?,
    val splitParts: List<AmountCandidate>,
    val settlementCandidate: AmountCandidate?,
    val ambiguityReason: String?
)

object AmountCandidateExtractor {

    private val NON_MONEY_MASK_PATTERNS = listOf(
        Regex("""(?:尾号|卡号)为?\s*\d{4,19}"""),
        Regex("""\*{2,4}\s*\d{4}"""),
        Regex("""(?<!\d)\d{11,28}(?!\d)"""),
        Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?"""),
        Regex("""\d{1,2}月\d{1,2}日"""),
        Regex("""\d{1,2}:\d{2}(?::\d{2})?"""),
        Regex("""(?:分|共)\d{1,2}期""")
    )

    // Captures currency-prefixed, suffix-unit, or keyword-led monetary amounts while keeping exact offsets.
    private val MONEY_SCAN_REGEX = Regex(
        """(?i)(USD|EUR|GBP|HKD|JPY|RMB|CNY|人民币|美元|欧元|英镑|港币|日元|HK\$|\$|€|£|[¥￥])\s*[+-]?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*(元|美元|欧元|英镑|港币|日元)?""" +
            """|(?<!\d)[+-]?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*(元|美元|欧元|英镑|港币|日元|(?:USD|EUR|GBP|HKD|JPY|RMB|CNY)(?![A-Za-z]))"""
    )

    private val FUNDING_CHANNELS = listOf(
        "微信余额", "微信零钱", "零钱通", "支付宝余额", "余额宝", "银行卡", "储蓄卡", "信用卡", "花呗", "白条", "数字人民币"
    )

    private val NON_BOOKABLE_ROLES = setOf(
        AmountRole.BALANCE,
        AmountRole.LIST_PRICE,
        AmountRole.DISCOUNT,
        AmountRole.INSTALLMENT_DUE
    )

    fun isRoleBookableAsPrimary(role: AmountRole): Boolean = role !in NON_BOOKABLE_ROLES

    fun extract(text: String): CandidateExtractionResult {
        val masked = maskNonMoneySpansPreservingOffsets(text)
        val rawCandidates = mutableListOf<AmountCandidate>()
        var nextIndex = 1

        for (match in MONEY_SCAN_REGEX.findAll(masked)) {
            val start = match.range.first
            val end = match.range.last + 1

            val numRaw: String
            val currToken: String
            if (match.groupValues[2].isNotEmpty()) {
                numRaw = match.groupValues[2]
                currToken = match.groupValues[3].ifEmpty { match.groupValues[1] }
            } else {
                numRaw = match.groupValues[4]
                currToken = match.groupValues[5]
            }

            // Skip bare "0元" authorization placeholders unless part of real amount
            val currency = normalizeCurrency(currToken)
            val scale = MoneyMath.defaultScaleForCurrency(currency)
            val minor = MoneyMath.parseDecimalToMinor(numRaw, scale) ?: continue
            if (minor == 0L) continue

            val clauseStart = findClauseStart(text, start)
            val beforeClause = text.substring(clauseStart, start)
            val nearBefore = text.substring(maxOf(0, start - 14), start)
            val afterClause = text.substring(end, minOf(text.length, end + 12))

            val (role, isEstimated, fundingHint) = classifyRole(
                beforeClause = beforeClause,
                nearBefore = nearBefore,
                afterClause = afterClause,
                currency = currency,
                fullText = text
            )

            rawCandidates.add(
                AmountCandidate(
                    candidateId = "c${nextIndex++}",
                    amountMinor = minor,
                    currency = currency,
                    scale = scale,
                    role = role,
                    startOffset = start,
                    endOffset = end,
                    isEstimated = isEstimated,
                    fundingChannelHint = fundingHint
                )
            )
        }

        val refined = refineSplitAndOrderRoles(text, rawCandidates)
        return selectCandidates(text, refined)
    }

    private fun maskNonMoneySpansPreservingOffsets(text: String): String {
        val chars = text.toCharArray()
        for (pattern in NON_MONEY_MASK_PATTERNS) {
            for (m in pattern.findAll(text)) {
                for (i in m.range) {
                    if (i in chars.indices) chars[i] = ' '
                }
            }
        }
        return String(chars)
    }

    private fun findClauseStart(text: String, pos: Int): Int {
        var i = pos - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '，' || ch == ',' || ch == '。' || ch == ';' || ch == '；' || ch == '\n' || ch == '＋' || ch == '+') {
                return i + 1
            }
            i--
        }
        return 0
    }

    private fun normalizeCurrency(token: String): String = when (token.trim().uppercase(Locale.US)) {
        "USD", "$", "美元" -> "USD"
        "EUR", "€", "欧元" -> "EUR"
        "GBP", "£", "英镑" -> "GBP"
        "HKD", "HK$", "港币" -> "HKD"
        "JPY", "日元" -> "JPY"
        else -> "CNY"
    }

    private fun classifyRole(
        beforeClause: String,
        nearBefore: String,
        afterClause: String,
        currency: String,
        fullText: String
    ): Triple<AmountRole, Boolean, String?> {
        val trimmedBefore = beforeClause.trim()
        val trimmedNear = nearBefore.trim()

        // 1. Estimated vs confirmed RMB settlement for foreign-currency spending
        if (currency == "CNY") {
            if (listOf("约合", "约折合", "估算", "参考折合", "预估折合").any { trimmedBefore.contains(it) }) {
                return Triple(AmountRole.ESTIMATED_SETTLEMENT, true, null)
            }
            if (listOf("折合", "折算", "折为", "实际入账", "入账人民币").any { trimmedBefore.contains(it) } &&
                listOf("USD", "EUR", "GBP", "HKD", "JPY", "$", "美元", "欧元", "英镑", "港币", "日元").any { fullText.contains(it, ignoreCase = true) }
            ) {
                return Triple(AmountRole.SETTLEMENT, false, null)
            }
        }

        // 2. Split funding channel detection (e.g., "微信余额 10 元＋银行卡 15 元，订单实付 25 元")
        val fundingHint = FUNDING_CHANNELS.firstOrNull { ch ->
            trimmedNear.endsWith(ch) || trimmedBefore.endsWith(ch) || trimmedBefore.contains("$ch ") || trimmedBefore.contains("$ch：") || trimmedBefore.contains("$ch:")
        }
        if (fundingHint != null && (fullText.contains("＋") || fullText.contains("+") || fullText.contains("组合支付") || fullText.contains("订单实付") || afterClause.trimStart().startsWith("＋") || afterClause.trimStart().startsWith("+"))) {
            // Only treat as split part if another funding part or explicit paid total exists
            val looksLikeRemainingBalance = listOf("可用余额", "当前余额", "活期余额", "账户余额").any { trimmedBefore.contains(it) }
            if (!looksLikeRemainingBalance && !trimmedBefore.contains("实付")) {
                return Triple(AmountRole.SPLIT_PART, false, fundingHint)
            }
        }

        // 3. Fee / service charge
        if (listOf("手续费", "服务费", "佣金", "另收费", "费率").any { trimmedBefore.contains(it) } ||
            (trimmedBefore.contains("另收") && trimmedBefore.contains("费"))
        ) {
            return Triple(AmountRole.FEE, false, null)
        }

        // 4. Installment due / bill reminder amount (not actual charge)
        if (listOf("首期应还", "本期应还", "每期应还", "应还金额", "应还", "待还", "最低还款").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.INSTALLMENT_DUE, false, null)
        }

        // 5. Remaining account balance / credit limit
        val isFundingBalanceWord = listOf("微信余额", "支付宝余额", "余额宝", "余额提现", "余额充值", "余额支付", "余额付款").any { trimmedBefore.contains(it) }
        if (!isFundingBalanceWord && listOf("可用余额", "当前余额", "活期余额", "账户余额", "剩余余额", "余额", "可用额度", "剩余额度", "额度").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.BALANCE, false, null)
        }

        // 6. Discount / coupon deduction
        if (listOf("优惠", "满减", "立减", "抵扣", "减免", "省", "折扣").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.DISCOUNT, false, null)
        }

        // 7. Original / list price
        if (listOf("原价", "标价", "商品总额", "订单原价").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.LIST_PRICE, false, null)
        }
        if (trimmedBefore.contains("订单") && !trimmedBefore.contains("实付") && fullText.contains("实付")) {
            return Triple(AmountRole.LIST_PRICE, false, null)
        }

        // 8. Explicit actual payment / spend
        if (listOf("实付", "实际支付", "实际扣款", "付款", "消费", "支出", "扣款", "支取", "划扣", "代扣", "支付", "分期购机", "分期消费", "购买", "垫付").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.PAID, false, fundingHint)
        }

        // 9. Principal transfer / withdrawal / income / refund / reimbursement
        if (listOf("提现", "到账", "入账", "转账", "转出", "转入", "收款", "退款", "退回", "还款", "充值", "分红", "返还", "报销", "工资", "代发", "存入").any { trimmedBefore.contains(it) }) {
            return Triple(AmountRole.PRINCIPAL, false, fundingHint)
        }

        return Triple(AmountRole.UNKNOWN, false, fundingHint)
    }

    private fun refineSplitAndOrderRoles(text: String, list: List<AmountCandidate>): List<AmountCandidate> {
        if (list.size <= 1) return list
        val paid = list.firstOrNull { it.role == AmountRole.PAID }
        val splits = list.filter { it.role == AmountRole.SPLIT_PART }
        // If we have a paid total and 2+ split parts whose sum equals paid total, keep them as SPLIT_PART;
        // otherwise if there is only 1 split part and NO separate paid total, promote it to PAID.
        return list.map { c ->
            if (c.role == AmountRole.SPLIT_PART && paid == null && splits.size == 1) {
                c.copy(role = AmountRole.PAID)
            } else if (c.role == AmountRole.UNKNOWN && paid != null && text.contains("订单") && c.startOffset < paid.startOffset && c.amountMinor > paid.amountMinor) {
                c.copy(role = AmountRole.LIST_PRICE)
            } else {
                c
            }
        }
    }

    private fun selectCandidates(text: String, candidates: List<AmountCandidate>): CandidateExtractionResult {
        if (candidates.isEmpty()) {
            return CandidateExtractionResult(emptyList(), null, null, emptyList(), null, null)
        }

        val feeCandidate = candidates.firstOrNull { it.role == AmountRole.FEE }
        val confirmedSettlement = candidates.firstOrNull { it.role == AmountRole.SETTLEMENT && !it.isEstimated }
        val estimatedSettlement = candidates.firstOrNull { it.role == AmountRole.ESTIMATED_SETTLEMENT || (it.role == AmountRole.SETTLEMENT && it.isEstimated) }
        val foreignPrimary = candidates.firstOrNull {
            it.currency != "CNY" && isRoleBookableAsPrimary(it.role) && it.role != AmountRole.FEE
        }

        val explicitPaid = candidates.firstOrNull { it.role == AmountRole.PAID }
        val principalList = candidates.filter { it.role == AmountRole.PRINCIPAL }
        val splitParts = candidates.filter { it.role == AmountRole.SPLIT_PART }
        val unknownBookable = candidates.firstOrNull { it.role == AmountRole.UNKNOWN }

        var ambiguityReason: String? = null

        // Check withdrawal arrival vs principal ambiguity (e.g., "提现 1,000 元，到账 999 元")
        if (text.contains("提现") && text.contains("到账") && principalList.size >= 2) {
            val withdrawAmt = principalList.first().amountMinor
            val arrivalAmt = principalList[1].amountMinor
            if (arrivalAmt < withdrawAmt) {
                ambiguityReason = "提现金额与到账金额存在差额，请确认手续费是否已从本金扣除，避免重复计算"
            }
        }

        // Foreign currency logic:
        // - If confirmed CNY settlement exists (e.g. "折合人民币72.30元" / "实际入账人民币72.30元"), use confirmed settlement as primary CNY amount.
        // - If ONLY estimated RMB exists (e.g. "约合人民币72.30元"), keep foreign original currency as primary and attach estimated settlement separately.
        val primary: AmountCandidate?
        val settlement: AmountCandidate?
        if (foreignPrimary != null) {
            if (confirmedSettlement != null) {
                primary = confirmedSettlement
                settlement = foreignPrimary
            } else {
                primary = foreignPrimary
                settlement = estimatedSettlement
            }
        } else {
            primary = explicitPaid
                ?: principalList.firstOrNull()
                ?: unknownBookable
                ?: splitParts.takeIf { it.size == 1 }?.firstOrNull()
            settlement = confirmedSettlement ?: estimatedSettlement
        }

        // Validate split parts only if they sum cleanly to primary (same currency)
        val validSplits = if (primary != null && splitParts.size >= 2 &&
            splitParts.all { it.currency == primary.currency } &&
            splitParts.sumOf { it.amountMinor } == primary.amountMinor
        ) {
            splitParts
        } else {
            emptyList()
        }

        // If the ONLY candidates present are non-bookable (e.g. only BALANCE or INSTALLMENT_DUE), primary stays null
        return CandidateExtractionResult(
            candidates = candidates,
            primaryCandidate = primary,
            feeCandidate = feeCandidate,
            splitParts = validSplits,
            settlementCandidate = settlement,
            ambiguityReason = ambiguityReason
        )
    }
}
