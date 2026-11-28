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

    // Matches standalone quote suffix "起" (e.g. "19.9元起", "19.9元起/月"), avoiding financial words like "起息"/"起算"
    private val STANDALONE_QI_SUFFIX_REGEX = Regex("""^起(?=$|[\s，。,;；)）!！?？/])""")

    // Unpaid / upcoming-charge phrases that mark a clause as NOT yet charged
    private val PENDING_OR_UPCOMING_KEYWORDS = listOf(
        "待付款", "待支付", "未付款", "未支付", "等待付款", "等待支付",
        "去付款", "去支付", "立即付款", "立即支付", "尽快付款", "尽快支付",
        "请付款", "请支付", "请及时付款", "请及时支付", "请完成支付", "请完成付款",
        "超时将取消", "超时未支付", "即将关闭",
        "即将扣款", "即将自动扣款", "即将续费", "即将自动续费", "将自动扣款", "将自动续费",
        "预计扣款", "预计扣", "预扣款", "拟扣款", "自动扣款预告", "续费提醒", "自动续费提醒", "到期提醒", "即将到期"
    )

    private val UPCOMING_CHARGE_REGEX = Regex(
        """(?:明日|明天|次日|即将|将于|预计|后天|下周|下月|次月|下期|\d+天后|\d+小时后|\d+分钟内)[将要会拟自动定期为你您按期预计完成进行于\s\d月日号时分:-]{0,12}(?:扣款|扣除|续费|付款|支付|划扣|代扣|(?:扣收|收取|另收|扣)?(?:手续费|服务费|佣金)(?=$|\s*(?:人民币|CNY|RMB|[¥￥$€£]|\d))|(?:扣收|收取|另收|扣)(?:费用|费)?(?=$|\s*(?:人民币|CNY|RMB|[¥￥$€£]|\d)))"""
    )

    internal fun splitClauses(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            if (ch == '，' || ch == ',' || ch == '。' || ch == ';' || ch == '；' ||
                ch == '\n' || ch == '!' || ch == '！' || ch == '?' || ch == '？' ||
                ch == '（' || ch == '(' || ch == '）' || ch == ')'
            ) {
                val part = buf.toString().trim()
                if (part.isNotEmpty()) out.add(part)
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    internal fun isPendingOrUpcomingClause(clause: String): Boolean {
        if (PENDING_OR_UPCOMING_KEYWORDS.any { clause.contains(it) }) {
            return true
        }
        return UPCOMING_CHARGE_REGEX.containsMatchIn(clause)
    }

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
            val clauseEnd = findClauseEnd(text, end)
            val beforeClause = text.substring(clauseStart, start)
            val fullClause = text.substring(clauseStart, clauseEnd)
            val clauseAfter = text.substring(end, clauseEnd)
            val nearBefore = text.substring(maxOf(0, start - 14), start)
            val afterClause = text.substring(end, minOf(text.length, end + 12))

            val (role, isEstimated, fundingHint) = classifyRole(
                beforeClause = beforeClause,
                fullClause = fullClause,
                clauseAfter = clauseAfter,
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
        var closedParens = 0
        while (i >= 0) {
            val ch = text[i]
            if (ch == '，' || ch == ',' || ch == '。' || ch == ';' || ch == '；' ||
                ch == '\n' || ch == '＋' || ch == '+' || ch == '!' || ch == '！' || ch == '?' || ch == '？'
            ) {
                return i + 1
            }
            if (ch == '）' || ch == ')') {
                closedParens++
            } else if (ch == '（' || ch == '(') {
                if (closedParens > 0) {
                    closedParens--
                } else {
                    return i + 1
                }
            }
            i--
        }
        return 0
    }

    private fun findClauseEnd(text: String, pos: Int): Int {
        var i = pos
        while (i < text.length) {
            val ch = text[i]
            if (ch == '，' || ch == ',' || ch == '。' || ch == ';' || ch == '；' ||
                ch == '\n' || ch == '＋' || ch == '+' || ch == '!' || ch == '！' || ch == '?' || ch == '？' ||
                ch == '（' || ch == '(' || ch == '）' || ch == ')'
            ) {
                return i
            }
            i++
        }
        return text.length
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
        fullClause: String,
        clauseAfter: String,
        nearBefore: String,
        afterClause: String,
        currency: String,
        fullText: String
    ): Triple<AmountRole, Boolean, String?> {
        val trimmedBefore = beforeClause.trim()
        val trimmedNear = nearBefore.trim()
        val trimmedFullClause = fullClause.trim()
        val trimmedClauseAfter = clauseAfter.trim()

        // 0. Pending / upcoming charge or bill-due clause MUST run BEFORE fee/split/settlement/paid checks
        //    so a future estimated fee (e.g. "本次已扣款15元，下期预计扣手续费2元") can never become a bookable FEE.
        if (listOf("首期应还", "本期应还", "每期应还", "应还金额", "应还", "待还", "最低还款").any { trimmedBefore.contains(it) } ||
            isPendingOrUpcomingClause(trimmedFullClause)
        ) {
            return Triple(AmountRole.INSTALLMENT_DUE, false, null)
        }

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

        // 3. Fee / service charge (already guarded against upcoming/due clauses in step 0)
        if (listOf("手续费", "服务费", "佣金", "另收费", "费率").any { trimmedBefore.contains(it) } ||
            (trimmedBefore.contains("另收") && trimmedBefore.contains("费"))
        ) {
            return Triple(AmountRole.FEE, false, null)
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

        // 7b. Non-transactional salary/rate/quote amounts (e.g. "薪资4500元/月", "月薪4500元", "19.9元起")
        val beforeForPay = trimmedBefore
            .replace("支付宝", "")
            .replace("微信支付分", "")
            .replace("支付密码", "")
            .replace("支付安全", "")
            .replace("支付保护", "")
            .replace("支付设置", "")
            .replace(Regex("""微信支付(?!凭证|成功)"""), "")
        val hasCompletedSettlementVerb = listOf(
            "实付", "实际支付", "实际扣款", "付款", "消费", "支出", "扣款", "已扣", "已付", "支取", "划扣", "代扣",
            "已支付", "支付成功", "成功支付", "分期购机", "分期消费", "垫付",
            "代发", "入账", "到账", "存入", "收款", "退款", "退回", "报销", "返还", "分红", "股息",
            "提现", "还款", "转账", "转出", "转入", "实发"
        ).any { beforeForPay.contains(it) } || listOf(
            "已入账", "入账", "已到账", "到账", "已存入", "存入",
            "已扣款", "已扣", "扣款成功", "支付成功", "付款成功", "已支付", "已退回", "退款成功"
        ).any { trimmedClauseAfter.contains(it) }
        val trimmedAfter = afterClause.trimStart()
        val hasRateOrQuoteSuffix = listOf("/月", "/天", "/日", "/小时", "/周", "/年").any { trimmedAfter.startsWith(it) } ||
            STANDALONE_QI_SUFFIX_REGEX.containsMatchIn(trimmedAfter)
        val looksLikeSalaryOrRateQuote = !hasCompletedSettlementVerb && (
            listOf("月薪", "日薪", "年薪", "底薪", "薪资", "起薪", "推荐岗位", "岗位信息", "岗位薪资", "预算").any { beforeForPay.contains(it) } ||
                hasRateOrQuoteSuffix
            )
        if (looksLikeSalaryOrRateQuote) {
            return Triple(AmountRole.LIST_PRICE, false, null)
        }

        // 8. Explicit actual payment / spend
        if (listOf("实付", "实际支付", "实际扣款", "付款", "消费", "支出", "扣款", "已扣", "已付", "支取", "划扣", "代扣", "支付", "分期购机", "分期消费", "购买", "垫付").any { beforeForPay.contains(it) }) {
            return Triple(AmountRole.PAID, false, fundingHint)
        }

        // 9. Principal transfer / withdrawal / income / refund / reimbursement
        if (listOf("提现", "到账", "入账", "转账", "转出", "转入", "收款", "退款", "退回", "还款", "充值", "分红", "返还", "报销", "工资", "代发", "存入").any { trimmedBefore.contains(it) } ||
            listOf("已入账", "入账", "已到账", "到账", "已存入", "存入", "已退回", "退款成功").any { trimmedClauseAfter.contains(it) }
        ) {
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
