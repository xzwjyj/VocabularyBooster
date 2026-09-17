package com.vocabularybooster.speech

/**
 * 语音命令（DOMAIN_MODEL §3.3）：v1 只实现 [MASTERED]；
 * PAUSE / RESUME / NEXT / REPLAY / EXIT 为预留枚举（关键词表已定义，启用须走需求变更流程）。
 * [UNKNOWN] 不是命令，是「识别文本未命中任何启用命令」的解析结果（AUDIO_ENGINE_SPEC §7，不误触发）。
 */
public enum class VoiceCommand {
    UNKNOWN,
    MASTERED,
    PAUSE,
    RESUME,
    NEXT,
    REPLAY,
    EXIT,
}

/**
 * CommandParser（AUDIO_ENGINE_SPEC §7，纯 Kotlin——跨平台复用）：
 * 识别**最终文本** → [VoiceCommand]。
 *
 * - 归一化：全角→半角（FF01–FF5E / 全角空格）+ 小写折叠 + **去除全部空白**（含内部——
 *   语音引擎分词伪影：Vosk 中文模型按字分词输出「会 了」，2026-09-14 vivo 实测）+ 忽略末尾标点；
 * - 匹配 = 归一化后与别名整串相等，或命中该别名的**近音兜底白名单**
 *   （[NEAR_HOMOPHONES_BY_ALIAS]，v1.7：Vosk 中文小模型近音误听「会了」→「坏了」，2026-09-16
 *   vivo 实测；白名单按别名归集、随真机日志增补），或命中**首/末字近音结构规则**
 *   （[STRUCTURAL_NEAR_CHARS_BY_ALIAS]，v2.0 用户裁决 2026-09-17：「会了」首字≈会 或
 *   末字≈了 即命中）。规则外仍无任意模糊匹配/NLP 容错；不误杀红线收窄为**近音集守卫**——
 *   首尾字均不在近音集（「你好」「hello」）→ UNKNOWN（繁体「會了」末字=了，随本裁决转为命中）；
 * - 命中别名且该命令在 enabled 集内 → [VoiceCommand.MASTERED]；其余一律 [VoiceCommand.UNKNOWN]
 *   （§9 不误杀词：UNKNOWN 由调用方「忽略并保持监听直到窗口超时」）；
 * - v1 启用集 = 仅 MASTERED（FR-12）；别名 = [DEFAULT_MASTERED_ALIASES]
 *   （Phase 5 Step 1 裁决 D4：v1 代码常量，Phase 8 设置页再接持久化）。
 */
public class CommandParser {

    public fun parse(rawText: String, aliases: Set<String>, enabled: Set<VoiceCommand>): VoiceCommand {
        val normalized = normalizeCommandText(rawText)
        val matched = aliases.any { matchesAlias(normalized, normalizeCommandText(it)) }
        return if (matched && VoiceCommand.MASTERED in enabled) VoiceCommand.MASTERED else VoiceCommand.UNKNOWN
    }

    /** 精确等值，或命中该别名的近音兜底白名单，或命中首/末字近音结构规则（均同函数归一化后比对）。 */
    private fun matchesAlias(normalized: String, normalizedAlias: String): Boolean =
        normalized == normalizedAlias ||
            NEAR_HOMOPHONES_BY_ALIAS[normalizedAlias]?.any { normalizeCommandText(it) == normalized } == true ||
            matchesHeadOrTailNearRule(normalized, normalizedAlias)

    /**
     * 首/末字近音结构规则（AUDIO_ENGINE_SPEC §7 v2.0，用户裁决 2026-09-17）：仅「会了」——
     * 识别文本**首字 ∈ 会近音字集 或 末字 ∈ 了近音字集**即命中（白名单逐形增补改为结构覆盖：
     * h 声母丢失「了了」、截断「会」「坏」、任意 X了/会X 组合与「…会了」句式全部兜住）。
     * 按别名归集（同近音白名单原则）：非本表别名（自定义别名集）不适用；空文本不命中。
     */
    private fun matchesHeadOrTailNearRule(normalized: String, normalizedAlias: String): Boolean {
        val nearChars = STRUCTURAL_NEAR_CHARS_BY_ALIAS[normalizedAlias]
        return normalized.isNotEmpty() && nearChars != null &&
            (normalized.first() in nearChars.first || normalized.last() in nearChars.second)
    }

    public companion object {
        /** FR-12 / FR-15 默认别名（与 DATABASE_SCHEMA §2.11 `settings.masteredAliases` 默认值一致）。 */
        public val DEFAULT_MASTERED_ALIASES: Set<String> = setOf("会了", "记住了", "掌握了")

        /** v1 启用命令集（FR-12：仅 MASTERED；其余枚举预留）。 */
        public val V1_ENABLED: Set<VoiceCommand> = setOf(VoiceCommand.MASTERED)

        /**
         * 近音兜底白名单（AUDIO_ENGINE_SPEC §7 v1.7/v1.9）：Vosk 中文小模型的已知近音误听形式，
         * key = 归一化别名、value = 该别名的近音变体（同函数归一化后整串比对）。
         * 证据驱动增补（2026-09-16 vivo 实测：「会了」→「坏了」/「回来」/断字截断「会」（了 被丢）；
         * 「回来/回了/会来」为 huì-le 的 {会,坏,换,回,惠,汇}×{了,啦,来} 同族高概率形式；
         * 2026-09-17 vivo 实测：「会了」→「了了」（h 声母丢失，huì-le → le-le））；Phase 8
         * 别名设置化时随别名扩展。白名单按别名归集：非默认别名集（如拉丁自定义别名）无近音兜底；
         * 无关短语（「你好」）与白名单外的任何文本仍 → UNKNOWN（D5 不误杀红线）。
         */
        public val NEAR_HOMOPHONES_BY_ALIAS: Map<String, Set<String>> = mapOf(
            "会了" to setOf("坏了", "换了", "会啦", "惠了", "汇了", "回来", "回了", "会来", "会", "了了"),
            "记住了" to setOf("记住啦", "记住咯"),
            "掌握了" to setOf("掌握啦", "掌握咯"),
        )

        /**
         * 首/末字近音结构规则（AUDIO_ENGINE_SPEC §7 v2.0，用户裁决 2026-09-17）：
         * key = 归一化别名、value =（首字近音集, 末字近音集）——识别文本首字命中前者**或**末字命中
         * 后者即判命中该别名。字符集 = 白名单实证误听字的全集：会 hui 族（Vosk 把「会」听成
         * 坏/换/回/惠/汇）、了 le 族（了/啦/咯/来）。证据驱动扩集；Phase 8 随别名设置化一并扩展。
         */
        public val STRUCTURAL_NEAR_CHARS_BY_ALIAS: Map<String, Pair<Set<Char>, Set<Char>>> = mapOf(
            "会了" to (
                setOf('会', '坏', '换', '回', '惠', '汇') to
                    setOf('了', '啦', '咯', '来')
                ),
        )
    }
}

/** §7 归一化：全角→半角 → 去全部空白（Vosk 按字分词「会 了」伪影）→ 小写折叠 → 去末尾标点（仅末尾——「，会了」不命中）。 */
internal fun normalizeCommandText(raw: String): String = raw
    .map(::toHalfWidth)
    .joinToString("")
    .filterNot { it.isWhitespace() }
    .lowercase()
    .trimEnd { it.isTrailingIgnorable() }

/** 全角 ASCII 区（U+FF01–U+FF5E）→ 半角（−0xFEE0）；全角空格（U+3000）→ 半角空格。 */
private fun toHalfWidth(c: Char): Char = when (c.code) {
    in FULL_WIDTH_ASCII_FIRST..FULL_WIDTH_ASCII_LAST -> (c.code - FULL_WIDTH_OFFSET).toChar()
    IDEOGRAPHIC_SPACE -> ' '
    else -> c
}

/** 末尾忽略集：标点（全角标点已折半角；。、…—· 无半角对应，单列）+ 空白。 */
private fun Char.isTrailingIgnorable(): Boolean =
    this in TRAILING_PUNCTUATION || isWhitespace()

private const val FULL_WIDTH_ASCII_FIRST: Int = 0xFF01
private const val FULL_WIDTH_ASCII_LAST: Int = 0xFF5E
private const val FULL_WIDTH_OFFSET: Int = 0xFEE0
private const val IDEOGRAPHIC_SPACE: Int = 0x3000
private const val TRAILING_PUNCTUATION: String = ".,!?;:~…·—、。"
