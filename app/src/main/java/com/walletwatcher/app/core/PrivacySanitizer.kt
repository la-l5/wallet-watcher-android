package com.walletwatcher.app.core

object PrivacySanitizer {
    private val idCardRegex = Regex("""(?<!\d)(\d{6})\d{8}(\d{3}[\dXx])(?!\d)""")
    private val bankCardRegex = Regex("""(?<!\d)(\d{4})\d{8,11}(\d{4})(?!\d)""")
    private val phoneRegex = Regex("""(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)""")
    private val longOrderRegex = Regex("""(?<!\d)(\d{16,28})(?!\d)""")

    fun sanitize(text: String, profile: UserProfile): Pair<String, List<String>> {
        val notes = LinkedHashSet<String>()
        var result = Regex("""(?<!\d)\d(?:[ -]?\d){9,17}[ -]?[\dXx](?!\d)""").replace(text) { match ->
            val compact = match.value.replace(" ", "").replace("-", "")
            if (compact.length in 13..19 || phoneRegex.matches(compact)) {
                notes.add("隐藏个人号码")
                "****" + compact.takeLast(4)
            } else match.value
        }

        result = idCardRegex.replace(result) { m ->
            notes.add("隐藏身份证号")
            "${m.groupValues[1]}********${m.groupValues[2]}"
        }
        result = bankCardRegex.replace(result) { m ->
            notes.add("隐藏完整银行卡号")
            "${m.groupValues[1]}****${m.groupValues[2]}"
        }
        result = phoneRegex.replace(result) { m ->
            notes.add("隐藏手机号")
            "${m.groupValues[1]}****${m.groupValues[2]}"
        }
        result = longOrderRegex.replace(result) { m ->
            notes.add("缩短超长流水号")
            "***${m.groupValues[1].takeLast(4)}"
        }

        val realName = profile.realName.trim()
        if (realName.isNotEmpty() && result.contains(realName)) {
            result = result.replace(realName, "[本人姓名]")
            notes.add("实名替换为[本人姓名]")
        }
        for (alias in profile.aliases) {
            val trimmed = alias.trim()
            if (trimmed.isNotEmpty() && result.contains(trimmed)) {
                result = result.replace(trimmed, "[本人昵称]")
                notes.add("本人昵称替换为[本人昵称]")
            }
        }

        return result to notes.toList()
    }
}

