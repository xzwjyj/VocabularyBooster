package com.vocabularybooster.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CommandParser 测试（AUDIO_ENGINE_SPEC §7 / TEST_PLAN TC-AE-10）：
 * 别名 / 大小写 / 全角 / 带标点 → MASTERED；未知（含空文本、噪音、繁体、前导标点、未启用）→ UNKNOWN。
 * 精确匹配语义（无模糊 NLP）；partial 结果不进解析（端口契约，编排器侧不送入）。
 */
class CommandParserTest {

    private val parser = CommandParser()
    private val aliases = CommandParser.DEFAULT_MASTERED_ALIASES
    private val enabled = CommandParser.V1_ENABLED

    // —— 命中：精确别名（TC-AE-10 别名行）——

    @Test
    fun exactMasteredAliasHits() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("记住了", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("掌握了", aliases, enabled))
    }

    // —— 空白（§7 trim + 全角空格折叠）——

    @Test
    fun surroundingWhitespaceIgnored() {
        assertEquals(VoiceCommand.MASTERED, parser.parse(" 会了 ", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("　会了　", aliases, enabled)) // U+3000 全角空格
    }

    // —— 末尾标点（§7「忽略末尾标点」：全角/半角/叠加）——

    @Test
    fun trailingPunctuationIgnored() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了。", aliases, enabled)) // U+3002
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了！", aliases, enabled)) // U+FF01 → '!'
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了，", aliases, enabled)) // U+FF0C → ','
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了!", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了.", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了!!", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("记住了; ", aliases, enabled)) // 标点+尾空白
    }

    // —— 大小写折叠（§7：别名集含拉丁词时生效；中文别名不受影响）——

    @Test
    fun caseFoldingForLatinAliases() {
        val latin = setOf("Got it")
        assertEquals(VoiceCommand.MASTERED, parser.parse("got it", latin, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("GOT IT!", latin, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("Ｇｏｔ ｉｔ", latin, enabled)) // 全角字母 → 折半角+小写
    }

    // —— 未命中 → UNKNOWN（不误触发，TC-AE-10 未知行 / §9 不误杀）——

    @Test
    fun unrelatedTextIsUnknown() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("你好", aliases, enabled))
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("hello", aliases, enabled))
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("我觉得这个词已经会了", aliases, enabled)) // 非精确 = 不命中
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("會了", aliases, enabled)) // 繁体：无简繁映射（规格未定义）
    }

    @Test
    fun emptyRecognitionIsUnknown() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("", aliases, enabled))
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("   ", aliases, enabled))
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("。", aliases, enabled)) // 只剩标点
    }

    // —— 边界：仅末尾标点忽略（前导标点不剥）；enabled 门（§7 签名第三参）——

    @Test
    fun leadingPunctuationDoesNotHit() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("，会了", aliases, enabled)) // §7 只忽略「末尾」
    }

    @Test
    fun disabledCommandNeverHits() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("会了", aliases, enabled = emptySet()))
    }
}
