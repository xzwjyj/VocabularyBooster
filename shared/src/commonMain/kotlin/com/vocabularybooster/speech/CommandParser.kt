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
 * - 归一化：trim + 全角→半角（FF01–FF5E / 全角空格）+ 小写折叠 + **忽略末尾标点**；
 * - 精确匹配语义：归一化后与别名集合整串相等才命中——不做模糊/近义/简繁转换
 *   （规格未定义，不自行扩展；「會了」繁体 → UNKNOWN）；
 * - 命中别名且该命令在 enabled 集内 → [VoiceCommand.MASTERED]；其余一律 [VoiceCommand.UNKNOWN]
 *   （§9 不误杀词：UNKNOWN 由调用方「忽略并保持监听直到窗口超时」）；
 * - v1 启用集 = 仅 MASTERED（FR-12）；别名 = [DEFAULT_MASTERED_ALIASES]
 *   （Phase 5 Step 1 裁决 D4：v1 代码常量，Phase 8 设置页再接持久化）。
 */
public class CommandParser {

    public fun parse(rawText: String, aliases: Set<String>, enabled: Set<VoiceCommand>): VoiceCommand {
        val normalized = normalizeCommandText(rawText)
        val matched = aliases.any { normalizeCommandText(it) == normalized }
        return if (matched && VoiceCommand.MASTERED in enabled) VoiceCommand.MASTERED else VoiceCommand.UNKNOWN
    }

    public companion object {
        /** FR-12 / FR-15 默认别名（与 DATABASE_SCHEMA §2.11 `settings.masteredAliases` 默认值一致）。 */
        public val DEFAULT_MASTERED_ALIASES: Set<String> = setOf("会了", "记住了", "掌握了")

        /** v1 启用命令集（FR-12：仅 MASTERED；其余枚举预留）。 */
        public val V1_ENABLED: Set<VoiceCommand> = setOf(VoiceCommand.MASTERED)
    }
}

/** §7 归一化：全角→半角 → trim → 小写折叠 → 去末尾标点/空白（仅末尾——「，会了」不命中）。 */
internal fun normalizeCommandText(raw: String): String = raw
    .map(::toHalfWidth)
    .joinToString("")
    .trim()
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
