package com.walletwatcher.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

data class AiParseResult(
    val direction: TxDirection,
    val amount: Double,
    val merchant: String,
    val itemSummary: String,
    val category: String,
    val paymentMethod: String,
    val needsReview: Boolean,
    val reasoning: String,
    val aiModeUsed: String
)

object AiClient {

    private const val SYSTEM_PROMPT = """你是一个严谨的个人财务记账 AI 会计，负责把手机通知栏抓取的脱敏文本解析为标准 JSON。
核心规则：
1. 只记录“外部 ↔ 内部钱包”的真实资金流动，所有内部流转一律判定为 INTERNAL_TRANSFER：
   - 提现、充值、余额宝/零钱通转入转出、理财/基金申赎 -> INTERNAL_TRANSFER
   - 信用卡/花呗/白条/美团月付/抖音月付还款 -> INTERNAL_TRANSFER
   - 银证转账、银行转证券、证券转银行、第三方存管 -> INTERNAL_TRANSFER
   - 向[本人姓名]或[本人昵称]转账 -> INTERNAL_TRANSFER（注意：银行开头的“尊敬的[本人姓名]”只是称呼，不是转账对手）
2. 先用后付：0元下单/已授权未扣款 -> IGNORED；确认收货真实扣款或花呗/信用卡单笔消费 -> EXPENSE。
3. 商户退款 -> REFUND（冲抵支出，不是 INCOME）。
4. 只输出严格 JSON：
{"direction":"EXPENSE|INCOME|REFUND|INTERNAL_TRANSFER|IGNORED","amount":0.00,"merchant":"","item_summary":"","category":"","payment_method":"","needs_review":false,"reasoning":""}"""

    private val categoryRules: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("瑞幸", "星巴克", "蜜雪冰城", "喜茶", "霸王茶姬", "美团外卖", "饿了么", "麦当劳", "肯德基", "海底捞", "餐厅", "餐饮", "咖啡", "奶茶", "烧烤", "拉面", "食堂", "便利店", "罗森", "全家", "7-11"),
        "交通" to listOf("地铁", "公交", "乘车码", "滴滴", "高德打车", "哈啰", "青桔", "美团单车", "12306", "中石油", "中石化", "加油", "高速", "ETC", "停车"),
        "购物" to listOf("淘宝", "天猫", "拼多多", "京东", "抖音电商", "唯品会", "山姆", "盒马", "超市", "优衣库", "迪卡侬", "苹果", "Apple", "数码"),
        "娱乐订阅" to listOf("腾讯视频", "爱奇艺", "网易云", "QQ音乐", "B站", "哔哩哔哩", "WPS", "iCloud", "会员", "续费", "Steam", "游戏", "电影", "猫眼"),
        "居住缴费" to listOf("电网", "电费", "水费", "燃气", "宽带", "话费", "中国移动", "中国联通", "中国电信", "物业", "房租"),
        "医疗健康" to listOf("医院", "药房", "药店", "体检", "挂号", "诊所"),
        "日用" to listOf("理发", "顺丰", "菜鸟", "快递", "洗衣", "充电宝", "街电", "怪兽充电", "美团充电")
    )

    private val knownMerchants = listOf(
        "瑞幸咖啡", "星巴克", "蜜雪冰城", "美团外卖", "拼多多", "淘宝", "京东", "抖音",
        "深圳地铁", "广州地铁", "上海地铁", "北京地铁", "滴滴出行", "怪兽充电",
        "腾讯视频", "网易云音乐", "顺丰速运", "盒马鲜生", "招商证券", "华泰证券", "东方财富",
        "肯德基", "麦当劳", "余额宝", "零钱通", "花呗", "美团月付", "抖音月付"
    )

    private val merchantPatterns = listOf(
        Regex("""(?:商户|收款方|交易对方|收款商户)[：:\s]*([^\s，。,;；()（）¥￥0-9]{2,20})"""),
        Regex("""(?:向|付款给)([^\s，。,;；()（）¥￥0-9]{2,16}?)(?:付款|转账|支付|消费|第三方存管|转出|转入|扣款|$)"""),
        Regex("""消费[，,\s]*([^\s，。,;；¥￥0-9]{2,16})"""),
        Regex("""来自([^\s，。,;；]{2,16})的"""),
        Regex("""([^\s，。,;；]{2,14}?)(?:自动扣款|免密扣款|先用后付|扣款)""")
    )

    private val trailingVerbRegex = Regex("""(?:付款|转账|支付|消费|转出|转入|扣款|第三方存管.*)$""")
    private val itemRegex = Regex("""(?:商品|备注|事由|说明|内容|购买)[：:\s]*([^，。\n；;]{2,28})""")

    fun parse(
        config: AiConfig,
        profile: UserProfile,
        payload: MergedPayload
    ): AiParseResult {
        if (config.isRealApiEnabled) {
            return try {
                callOpenAiCompatible(config, payload)
            } catch (e: Exception) {
                val fallback = parseMock(profile, payload)
                fallback.copy(
                    aiModeUsed = "mock_fallback",
                    reasoning = "[真实API异常已自动降级: ${e.message ?: e.javaClass.simpleName}] ${fallback.reasoning}"
                )
            }
        }
        return parseMock(profile, payload)
    }

    private fun callOpenAiCompatible(config: AiConfig, payload: MergedPayload): AiParseResult {
        var endpoint = config.baseUrl.trim().trimEnd('/')
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint = "$endpoint/chat/completions"
        }

        val userPrompt = buildString {
            appendLine("请解析以下合并并脱敏后的手机动账通知：")
            appendLine("---")
            appendLine(payload.sanitizedText)
            appendLine("---")
            append("辅助参考：预提取金额=${payload.amount}，预提取支付方式=${payload.paymentMethod.ifBlank { "未知" }}，")
            append("预检提示=${payload.preInternalReason ?: if (payload.isZeroAuth) "0元先用后付授权" else "无"}。")
        }

        val bodyObj = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = config.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(bodyObj.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawResp = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val respJson = JSONObject(rawResp)
            var content = respJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            if (content.startsWith("```")) {
                content = content.replace(Regex("""^```(?:json)?\s*"""), "")
                    .replace(Regex("""\s*```$"""), "")
                    .trim()
            }
            val parsed = JSONObject(content)
            val dir = runCatching {
                TxDirection.valueOf(parsed.optString("direction", "EXPENSE").uppercase())
            }.getOrDefault(TxDirection.EXPENSE)

            val amount = round2(parsed.optDouble("amount", payload.amount ?: 0.0).let {
                if (it.isNaN()) (payload.amount ?: 0.0) else it
            })

            return AiParseResult(
                direction = dir,
                amount = amount,
                merchant = parsed.optString("merchant", "").ifBlank { extractMerchant(payload.sanitizedText) },
                itemSummary = parsed.optString("item_summary", "").ifBlank { "交易记录" },
                category = parsed.optString("category", "").ifBlank { "日用" },
                paymentMethod = parsed.optString("payment_method", "").ifBlank { payload.paymentMethod.ifBlank { "默认钱包" } },
                needsReview = parsed.optBoolean("needs_review", false),
                reasoning = parsed.optString("reasoning", "由真实大模型 API 解析"),
                aiModeUsed = "real_api:${config.model}"
            )
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseMock(profile: UserProfile, payload: MergedPayload): AiParseResult {
        val text = payload.sanitizedText
        val amount = round2(payload.amount ?: 0.0)
        val payMethod = payload.paymentMethod.ifBlank { "默认钱包" }

        // 1. 0-yuan pay-later authorization
        val zeroKeywords = listOf("0元下单", "0.00元下单", "已开通先用后付", "已使用先用后付", "先享后付已授权", "确认收货后自动扣款")
        if (payload.isZeroAuth || zeroKeywords.any { text.contains(it) }) {
            val merchant = extractMerchant(text)
            return AiParseResult(
                direction = TxDirection.IGNORED,
                amount = amount,
                merchant = merchant,
                itemSummary = "先用后付0元占坑，尚未实际扣款",
                category = "忽略提醒",
                paymentMethod = payMethod,
                needsReview = false,
                reasoning = "识别为先用后付0元下单/授权，遵循“认扣款不认占坑”原则自动忽略。",
                aiModeUsed = "mock"
            )
        }

        // 2. Internal transfer checks
        val internalReason = payload.preInternalReason ?: detectInternalReason(text, profile)
        if (internalReason != null) {
            var merchant = extractMerchant(text)
            if (merchant == "未知商户") {
                merchant = profile.brokers.firstOrNull { text.contains(it) }
                    ?: when {
                        text.contains("证券") -> "证券资金账户"
                        text.contains("花呗") -> "花呗"
                        text.contains("信用卡") -> "信用卡还款"
                        text.contains("余额宝") -> "余额宝"
                        text.contains("零钱通") -> "零钱通"
                        else -> "本人内部账户"
                    }
            }
            return AiParseResult(
                direction = TxDirection.INTERNAL_TRANSFER,
                amount = amount,
                merchant = merchant,
                itemSummary = extractItemSummary(text, merchant, TxDirection.INTERNAL_TRANSFER),
                category = "内部流转",
                paymentMethod = payMethod,
                needsReview = false,
                reasoning = internalReason,
                aiModeUsed = "mock"
            )
        }

        // 3. Refund
        val refundKeywords = listOf("退款", "已退回", "退回原账户", "退货成功", "原路退回")
        if (payload.roughDirection == "REFUND" || refundKeywords.any { text.contains(it) }) {
            val merchant = extractMerchant(text)
            return AiParseResult(
                direction = TxDirection.REFUND,
                amount = amount,
                merchant = merchant,
                itemSummary = extractItemSummary(text, merchant, TxDirection.REFUND),
                category = "退款冲抵",
                paymentMethod = payMethod,
                needsReview = false,
                reasoning = "识别为商户退款，自动冲抵支出而不虚增收入。",
                aiModeUsed = "mock"
            )
        }

        // 4. Income vs Expense
        val incomeCues = listOf("收款", "收入", "入账", "到账", "工资", "代发", "收到", "红包", "转入", "报销", "分红", "股息")
        val expenseCues = listOf("支出", "付款", "消费", "扣款", "支付成功", "向", "已付", "免密扣款", "自动续费", "转出")
        val strongExpenseCues = listOf("支付成功", "扣款", "付款给", "消费")

        val direction: TxDirection
        val reasoning: String
        if (payload.roughDirection == "IN" || (incomeCues.any { text.contains(it) } && strongExpenseCues.none { text.contains(it) })) {
            direction = TxDirection.INCOME
            reasoning = "资金由外部流入本人账户，判定为真实收入。"
        } else if (payload.roughDirection == "OUT" || expenseCues.any { text.contains(it) }) {
            direction = TxDirection.EXPENSE
            reasoning = when {
                listOf("花呗", "信用卡", "月付", "白条").any { text.contains(it) } ->
                    "信用支付单笔消费，按“消费时记支出、月底还款忽略”原则入账。"
                listOf("免密", "极速", "乘车码", "自动扣款", "确认收货").any { text.contains(it) } ->
                    "免密/极速/先用后付实际扣款已触发，记为真实支出。"
                else -> "资金流向外部商户或他人，判定为真实支出。"
            }
        } else {
            direction = TxDirection.EXPENSE
            reasoning = "通知表述较模糊，暂记为支出并标记待确认。"
        }

        val merchant = extractMerchant(text)
        val needsReview = merchant == "未知商户" && reasoning.contains("模糊")

        return AiParseResult(
            direction = direction,
            amount = amount,
            merchant = merchant,
            itemSummary = extractItemSummary(text, merchant, direction),
            category = inferCategory(text, merchant, direction),
            paymentMethod = payMethod,
            needsReview = needsReview,
            reasoning = reasoning,
            aiModeUsed = "mock"
        )
    }

    private fun detectInternalReason(text: String, profile: UserProfile): String? {
        if (listOf("银证转账", "银行转证券", "证券转银行", "第三方存管", "资金存管").any { text.contains(it) }
            || profile.brokers.any { text.contains(it) }
        ) {
            return "银行与证券账户互转（银证转账），资产仍在本人名下。"
        }
        if (listOf("信用卡还款", "花呗还款", "白条还款", "月付还款", "自动还款", "账单还款").any { text.contains(it) }) {
            return "信用账单还款，消费时已记账，还款属于内部流转。"
        }
        if (listOf("零钱提现", "余额提现", "提现至", "提现到", "零钱充值", "余额充值").any { text.contains(it) }) {
            return "本人钱包与银行卡之间的充值/提现，属于内部流转。"
        }
        if (listOf("余额宝转入", "余额宝转出", "零钱通转入", "零钱通转出", "朝朝宝", "货币基金申购", "基金赎回", "理财申购", "理财赎回").any { text.contains(it) }) {
            return "活期理财/基金内部划转，不计入日常消费。"
        }
        val selfTargetPatterns = listOf(
            "向[本人姓名]", "给[本人姓名]", "对方：[本人姓名]", "对方:[本人姓名]",
            "收款方：[本人姓名]", "收款人：[本人姓名]", "向[本人昵称]", "给[本人昵称]"
        )
        if (selfTargetPatterns.any { text.contains(it) } ||
            ((text.contains("[本人姓名]") || text.contains("[本人昵称]")) && text.contains("转账") && !text.contains("尊敬的[本人姓名]"))
        ) {
            return "交易对方命中本地“自己人档案”，判定为自己给自己转账。"
        }
        return null
    }

    internal fun extractMerchant(text: String): String {
        val ignoredTokens = setOf("微信支付", "支付宝", "人民币", "银行卡", "账户", "您", "你的", "财付通")
        for (pat in merchantPatterns) {
            val m = pat.find(text) ?: continue
            var candidate = m.groupValues[1].trim('：', ':', '(', ')', '（', '）', '[', ']', '【', '】')
            candidate = trailingVerbRegex.replace(candidate, "").trim()
            if (candidate.length >= 2 && candidate !in ignoredTokens) {
                return candidate
            }
        }
        for (kw in knownMerchants) {
            if (text.contains(kw)) return kw
        }
        return "未知商户"
    }

    private fun extractItemSummary(text: String, merchant: String, direction: TxDirection): String {
        itemRegex.find(text)?.let { return it.groupValues[1].trim() }
        return when (direction) {
            TxDirection.REFUND -> "$merchant 订单退款"
            TxDirection.INTERNAL_TRANSFER -> when {
                text.contains("银证") || text.contains("证券") -> "银行卡与证券账户互转"
                text.contains("还款") -> "信用账单还款"
                text.contains("提现") -> "零钱/余额提现至银行卡"
                text.contains("充值") -> "银行卡充值至钱包"
                text.contains("余额宝") || text.contains("零钱通") || text.contains("理财") -> "活期理财内部划转"
                else -> "本人名下账户互转"
            }
            TxDirection.IGNORED -> "先用后付0元授权"
            else -> when {
                text.contains("工资") || text.contains("代发") -> "工资/薪资入账"
                text.contains("红包") -> "红包往来"
                text.contains("乘车") || text.contains("地铁") || text.contains("公交") -> "公共交通乘车扣费"
                text.contains("续费") || text.contains("会员") -> "$merchant 自动续费/会员"
                text.contains("充电宝") || text.contains("怪兽充电") -> "共享充电宝结算"
                else -> "$merchant 交易"
            }
        }
    }

    private fun inferCategory(text: String, merchant: String, direction: TxDirection): String {
        return when (direction) {
            TxDirection.INTERNAL_TRANSFER -> "内部流转"
            TxDirection.IGNORED -> "忽略提醒"
            TxDirection.REFUND -> "退款冲抵"
            TxDirection.INCOME -> when {
                listOf("工资", "薪资", "代发", "奖金", "绩效").any { text.contains(it) } -> "工资收入"
                listOf("分红", "股息", "利息", "收益").any { text.contains(it) } -> "投资收益"
                listOf("转账", "红包", "AA", "收款").any { text.contains(it) } -> "人情转账"
                else -> "副业/其他收入"
            }
            TxDirection.EXPENSE -> {
                val haystack = "$merchant $text"
                categoryRules.firstOrNull { (_, kws) -> kws.any { haystack.contains(it) } }?.first
                    ?: if (listOf("转账", "红包", "发给").any { haystack.contains(it) }) "人情转账" else "日用"
            }
        }
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
}
