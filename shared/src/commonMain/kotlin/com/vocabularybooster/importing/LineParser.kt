package com.vocabularybooster.importing

/**
 * 行解析（IMPORT_SPEC §3，FR-14）：纯函数、逐行独立、无状态。
 * 输入 = 已解码、已剥离 BOM、不含行尾符的单行原文。
 *
 * 分隔符优先级：Tab > 2+ 空格 > 单空格 > 逗号（半/全角）> 分号（半/全角）> 冒号（半/全角）；
 * 每级取**首次出现**位置尝试分割，「成功」= 左侧为合法词 + 右侧非空；
 * 全部分隔符失败后按「仅词」整行校验（多词短语合法：词内空格是词法字符）；
 * 任一失败 → [ParsedLine.Invalid]（保留原文供报告样例，最多 20 条由引擎截取）。
 */
public object LineParser {

    /** 译文字段长度上限（IMPORT_SPEC §3：分隔符后整段 ≤ 256，超限整行非法）。 */
    public const val MAX_TRANSLATION_LENGTH: Int = 256

    private const val WORD_PATTERN = "^[A-Za-z][A-Za-z\\u2019'\\- ]{0,63}$"
    private val wordRegex = Regex(WORD_PATTERN)

    public fun parse(raw: String): ParsedLine {
        val line = raw.trim()
        return when {
            line.isEmpty() -> ParsedLine.Ignored
            else -> translationSplitOf(line, raw) ?: wordOnlyOrInvalid(line, raw)
        }
    }

    /**
     * 分隔符优先级尝试（每级首现位置）；「成功」= 左侧合法词 + 右侧非空。
     * 译文超限 → 整行 invalid（不再尝试更宽分割——超限时整行必然 >64 字符，
     * 仅词校验也必然失败，直接短路，IMPORT_SPEC §3）。
     */
    private fun translationSplitOf(line: String, raw: String): ParsedLine? {
        for ((left, right) in splitCandidates(line)) {
            if (wordRegex.matches(left) && right.isNotEmpty()) {
                return if (right.length > MAX_TRANSLATION_LENGTH) {
                    ParsedLine.Invalid(raw)
                } else {
                    ParsedLine.WordWithTranslation(
                        text = left,
                        normalizedText = left.lowercase(),
                        translation = right,
                    )
                }
            }
        }
        return null
    }

    /** 仅词行（多词短语：空格属词法字符）；否则非法（保留原文供报告）。 */
    private fun wordOnlyOrInvalid(line: String, raw: String): ParsedLine =
        if (wordRegex.matches(line)) {
            ParsedLine.WordOnly(text = line, normalizedText = line.lowercase())
        } else {
            ParsedLine.Invalid(raw)
        }

    /** 按优先级产出候选分割（左侧已 trimEnd、右侧已 trim）。 */
    private fun splitCandidates(line: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()

        line.indexOf('\t').takeIf { it > 0 }?.let { candidates += splitAt(line, it, 1) }

        runOfTwoOrMoreSpaces(line)?.let { (start, len) -> candidates += splitAt(line, start, len) }

        line.indexOf(' ').takeIf { it > 0 }?.let { candidates += splitAt(line, it, 1) }

        firstOfAny(line, ',', '，')?.let { (start, len) -> candidates += splitAt(line, start, len) }
        firstOfAny(line, ';', '；')?.let { (start, len) -> candidates += splitAt(line, start, len) }
        firstOfAny(line, ':', '：')?.let { (start, len) -> candidates += splitAt(line, start, len) }

        return candidates
    }

    private fun splitAt(line: String, sepIndex: Int, sepLength: Int): Pair<String, String> =
        line.substring(0, sepIndex).trimEnd() to line.substring(sepIndex + sepLength).trim()

    /** 首个「≥2 连续空格」游程（多空格优先于单空格）。 */
    private fun runOfTwoOrMoreSpaces(line: String): Pair<Int, Int>? {
        var i = 0
        while (i < line.length) {
            if (line[i] == ' ') {
                var end = i
                while (end < line.length && line[end] == ' ') end++
                if (end - i >= 2) return i to (end - i)
                i = end
            } else {
                i++
            }
        }
        return null
    }

    /** 任一候选字符的首现（返回 位置 + 字符宽度 1）。 */
    private fun firstOfAny(line: String, vararg chars: Char): Pair<Int, Int>? {
        var best = -1
        for (c in chars) {
            val idx = line.indexOf(c)
            if (idx >= 0 && (best < 0 || idx < best)) best = idx
        }
        return if (best > 0) best to 1 else null
    }
}

/** 解析结果（IMPORT_SPEC §3）：四态，引擎据此计数/去重/落库。 */
public sealed interface ParsedLine {

    /** 空行（trim 后为空）：跳过，计 ignored（不计入有效行）。 */
    public data object Ignored : ParsedLine

    /** 仅词行（无译文）：`pendingTranslation = null`。 */
    public data class WordOnly(val text: String, val normalizedText: String) : ParsedLine

    /** 词 + 译文行：译文 = 分隔符后整段（可含逗号等原样保留，边界 #8/#9）。 */
    public data class WordWithTranslation(
        val text: String,
        val normalizedText: String,
        val translation: String,
    ) : ParsedLine

    /** 非法行（词字符越界/中文开头/含数字/译文超限等）：保留原文供报告。 */
    public data class Invalid(val raw: String) : ParsedLine
}
