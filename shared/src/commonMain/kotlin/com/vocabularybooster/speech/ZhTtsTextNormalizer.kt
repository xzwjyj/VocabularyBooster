package com.vocabularybooster.speech

/**
 * ZH TTS 文本数字归一化（SCR-ZHNUM，FR-24 缺陷修复，AUDIO_ENGINE_SPEC §8）：
 * zipvoice 前端（sherpa-onnx MatchaTtsLexicon）对非 CJK 词硬编码 espeak en-us——
 * 中文段里的半角数字串（如「由3000至6000名」）落入该分支被英读（"three thousand"）。
 * 本函数在合成前把中文文本中「自由」数字串预转中文读法（三千 / 六千 / 一百…），
 * 转换用字（零一二三四五六七八九十百千万亿点）均在 zipvoice lexicon 内，正常拼音合成。
 *
 * 规则：
 * - 整数按位权（十/百/千/万/亿分组，组内零填充；数字最高位的一十省一：10→十）；
 * - 小数 = 整数部分 +「点」+ 小数逐位（3.14→三点一四）；
 * - 前导零串逐位读（007→零零七；0→零；0.5→零点五）；
 * - 紧邻 ASCII 字母的数字不转（MP3 / 3D / 1990s 保持 espeak 英读——英文混排语境）；
 * - 整数部分超 12 位（≥万亿，两级大单位以上）防御性不转（词典释义不会出现，防长数字乱读）。
 *
 * 只用于 ZH_CN 神经段（piper EN 段本就英读数字；系统 TTS 原生读中文数字，不经此函数）。
 */
public object ZhTtsTextNormalizer {

    private val DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val GROUP_UNITS = arrayOf("", "十", "百", "千")
    private val NUMBER_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")

    private const val MAX_INT_DIGITS: Int = 12
    private const val DIGITS_PER_GROUP: Int = 4

    /** 把文本中的自由数字串替换为中文读法；无数字或全部守卫命中时原样返回。 */
    public fun normalize(text: String): String = NUMBER_REGEX.replace(text) { match ->
        val first = match.range.first
        val last = match.range.last
        val prevLatin = first > 0 && text[first - 1].isAsciiLetter()
        val nextLatin = last + 1 < text.length && text[last + 1].isAsciiLetter()
        if (prevLatin || nextLatin) match.value else convertNumber(match.value)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** 单个数字串（可含小数）→ 中文读法。 */
    private fun convertNumber(raw: String): String {
        val dot = raw.indexOf('.')
        val intPart = if (dot >= 0) raw.substring(0, dot) else raw
        val fracPart = if (dot >= 0) raw.substring(dot + 1) else ""
        if (intPart.length > MAX_INT_DIGITS) return raw

        // 前导零（含全零）串逐位读：007→零零七、0→零、09.5→零九点五
        val intText = if (intPart.length > 1 && intPart[0] == '0') {
            intPart.map { DIGITS[it - '0'] }.joinToString("")
        } else {
            convertInteger(intPart)
        }
        val fracText = if (fracPart.isEmpty()) "" else "点" + fracPart.map { DIGITS[it - '0'] }.joinToString("")
        return intText + fracText
    }

    /** 1–12 位无前导零整数 → 位权读法（十万 / 一亿 / 一万零一十二）。 */
    private fun convertInteger(s: String): String {
        val len = s.length
        val sb = StringBuilder()
        var started = false // 已输出任何高位数字（全零组不置位）
        var pendingZero = false // 跨组零待补（组间全零组占位）
        var i = 0
        while (i < len) {
            val groupLen = if (i == 0) ((len - 1) % DIGITS_PER_GROUP) + 1 else DIGITS_PER_GROUP
            val bigUnit = when ((len - i - groupLen) / DIGITS_PER_GROUP) {
                2 -> "亿"
                1 -> "万"
                else -> ""
            }
            val group = s.substring(i, i + groupLen)
            val stripped = group.dropWhile { it == '0' }
            if (stripped.isEmpty()) {
                if (started) pendingZero = true
            } else {
                // 组间空档零：前置组后有空位（前导零或中间全零组）
                if (started && (pendingZero || stripped.length < group.length)) sb.append('零')
                sb.append(groupToChinese(stripped)).append(bigUnit)
                started = true
                pendingZero = false
            }
            i += groupLen
        }
        // 最高位一十省一（仅整个数字的头部：10→十；组内保一：110→一百一十）；全零串兜底零
        if (sb.isEmpty()) return "零"
        return if (sb.startsWith("一十")) sb.deleteCharAt(0).toString() else sb.toString()
    }

    /** 1–4 位组内转换（内部零填充）：12→一十二、105→一百零五、0012 由调用方剥前导零。 */
    private fun groupToChinese(s: String): String {
        val sb = StringBuilder()
        var zeroPending = false
        var started = false
        for (i in s.indices) {
            val d = s[i] - '0'
            if (d == 0) {
                zeroPending = started
            } else {
                if (zeroPending) {
                    sb.append('零')
                    zeroPending = false
                }
                sb.append(DIGITS[d]).append(GROUP_UNITS[s.length - 1 - i])
                started = true
            }
        }
        return sb.toString()
    }
}
