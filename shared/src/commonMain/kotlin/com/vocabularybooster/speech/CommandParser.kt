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
 *   vivo 实测；白名单按别名归集、随真机日志增补）。除白名单外仍为精确语义——不做任意模糊
 *   匹配/简繁转换/NLP 容错（「你好」「會了」→ UNKNOWN，不误杀红线不变）；
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

    /** 精确等值，或命中该别名的近音兜底白名单（[NEAR_HOMOPHONES_BY_ALIAS]，同函数归一化后比对）。 */
    private fun matchesAlias(normalized: String, normalizedAlias: String): Boolean =
        normalized == normalizedAlias ||
            NEAR_HOMOPHONES_BY_ALIAS[normalizedAlias]?.any { normalizeCommandText(it) == normalized } == true

    public companion object {
        /** FR-12 / FR-15 默认别名（与 DATABASE_SCHEMA §2.11 `settings.masteredAliases` 默认值一致）。 */
        public val DEFAULT_MASTERED_ALIASES: Set<String> = setOf("会了", "记住了", "掌握了")

        /** v1 启用命令集（FR-12：仅 MASTERED；其余枚举预留）。 */
        public val V1_ENABLED: Set<VoiceCommand> = setOf(VoiceCommand.MASTERED)

        /**
         * 近音兜底白名单（AUDIO_ENGINE_SPEC §7 v1.7）：Vosk 中文小模型的已知近音误听形式，
         * key = 归一化别名、value = 该别名的近音变体（同函数归一化后整串比对）。
         * 证据驱动增补（2026-09-16 vivo 实测：「会了」→「坏了」/「回来」/断字截断「会」（了 被丢）；
         * 「回来/回了/会来」为 huì-le 的 {会,坏,换,回,惠,汇}×{了,啦,来} 同族高概率形式）；Phase 8
         * 别名设置化时随别名扩展。白名单按别名归集：非默认别名集（如拉丁自定义别名）无近音兜底；
         * 无关短语（「你好」）与白名单外的任何文本仍 → UNKNOWN（D5 不误杀红线）。
         */
        public val NEAR_HOMOPHONES_BY_ALIAS: Map<String, Set<String>> = mapOf(
            "会了" to setOf("坏了", "换了", "会啦", "惠了", "汇了", "回来", "回了", "会来", "会"),
            "记住了" to setOf("记住啦", "记住咯"),
            "掌握了" to setOf("掌握啦", "掌握咯"),
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
