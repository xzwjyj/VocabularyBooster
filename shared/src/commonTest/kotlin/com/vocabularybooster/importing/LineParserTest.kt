package com.vocabularybooster.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 行解析（IMPORT_SPEC §3）：TC-IMP-02 六种分隔符 + 优先级（Tab > 2+ 空格 > 单空格 >
 * 逗号(半/全角) > 分号 > 冒号；每级首现位置，左侧合法词 + 右侧非空才算成功，
 * 高优先级候选失败逐级下探）+ 译文整段保留（可含逗号）；
 * TC-IMP-06 非法行（数字/中文开头/词超长/译文超限/分隔符后空）→ Invalid。
 */
class LineParserTest {

    @Test
    fun tabSplitsWordAndTranslation() {
        val parsed = LineParser.parse("apple\t苹果")
        val withTranslation = assertIs<ParsedLine.WordWithTranslation>(parsed)
        assertEquals("apple", withTranslation.text)
        assertEquals("apple", withTranslation.normalizedText)
        assertEquals("苹果", withTranslation.translation)
    }

    @Test
    fun multiSpaceRunBeatsSingleSpace() {
        // 2+ 空格（首现 index 11）优先于单空格（index 5）：左侧为多词短语，合法
        val parsed = LineParser.parse("hello world  again today")
        val withTranslation = assertIs<ParsedLine.WordWithTranslation>(parsed)
        assertEquals("hello world", withTranslation.text)
        assertEquals("again today", withTranslation.translation)
    }

    @Test
    fun singleSpaceBeatsComma() {
        val parsed = LineParser.parse("hello world,你好")
        val withTranslation = assertIs<ParsedLine.WordWithTranslation>(parsed)
        assertEquals("hello", withTranslation.text)
        assertEquals("world,你好", withTranslation.translation)
    }

    @Test
    fun higherPriorityCandidateFallsThroughWhenLeftIsNotAWord() {
        // Tab 候选左侧「apple,香蕉」非法 → 下探到逗号级（首现）：apple | 香蕉\t苹果
        val parsed = LineParser.parse("apple,香蕉\t苹果")
        val withTranslation = assertIs<ParsedLine.WordWithTranslation>(parsed)
        assertEquals("apple", withTranslation.text)
        assertEquals("香蕉\t苹果", withTranslation.translation)
    }

    @Test
    fun halfAndFullWidthSeparatorsSplitAtFirstOccurrence() {
        // 逗号（半/全角取首现）
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "A，B"),
            LineParser.parse("apple，A，B"),
        )
        // 分号
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "苹果"),
            LineParser.parse("apple;苹果"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "苹果"),
            LineParser.parse("apple；苹果"),
        )
        // 冒号
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "苹果"),
            LineParser.parse("apple:苹果"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "苹果"),
            LineParser.parse("apple：苹果"),
        )
    }

    @Test
    fun translationKeepsCommasAndWhitespaceIsTrimmed() {
        // 译文 = 分隔符后整段（逗号原样保留，边界 #8/#9）；分隔符两侧空白剥净
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "苹果，香蕉，梨"),
            LineParser.parse("apple,苹果，香蕉，梨"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "red apple 苹果"),
            LineParser.parse("  apple \t red apple 苹果  "),
        )
    }

    @Test
    fun wordOnlyLineAcceptsHyphenAndApostrophes() {
        assertEquals(ParsedLine.WordOnly("hello", "hello"), LineParser.parse("hello"))
        // 单空格是分隔符级（§3）：双词行按「词+译文」解析——take | off；
        // 多词短语仅在无有效分割时以整词存活（连字符是词法字符）
        assertEquals(
            ParsedLine.WordWithTranslation("take", "take", "off"),
            LineParser.parse("take off"),
        )
        assertEquals(ParsedLine.WordOnly("take-off", "take-off"), LineParser.parse("take-off"))
        // 直撇号与弯撇号（’）
        assertEquals(ParsedLine.WordOnly("don't", "don't"), LineParser.parse("don't"))
        assertEquals(
            ParsedLine.WordOnly("it’s", "it’s"),
            LineParser.parse("it’s"),
        )
    }

    @Test
    fun blankLinesAreIgnored() {
        assertEquals(ParsedLine.Ignored, LineParser.parse(""))
        assertEquals(ParsedLine.Ignored, LineParser.parse("   "))
    }

    @Test
    fun digitContainingOrChineseStartingLinesAreInvalid() {
        assertIs<ParsedLine.Invalid>(LineParser.parse("3apple 苹果"))
        assertIs<ParsedLine.Invalid>(LineParser.parse("abc123"))
        // 单空格候选左侧「苹果」非法，整行也非词 → Invalid
        assertIs<ParsedLine.Invalid>(LineParser.parse("苹果 apple"))
        assertIs<ParsedLine.Invalid>(LineParser.parse("|||"))
    }

    @Test
    fun wordLengthLimitIs64() {
        assertEquals(ParsedLine.WordOnly("a" + "b".repeat(63), "a" + "b".repeat(63)),
            LineParser.parse("a" + "b".repeat(63)))
        assertIs<ParsedLine.Invalid>(LineParser.parse("a" + "b".repeat(64)))
    }

    @Test
    fun translationLengthLimitIs256WholeSegment() {
        // 恰 256：合法（译文整段）
        val atLimit = "果".repeat(256)
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", atLimit),
            LineParser.parse("apple\t$atLimit"),
        )
        // 257：译文超限 → 不再下探更宽分割，整行 Invalid
        assertIs<ParsedLine.Invalid>(LineParser.parse("apple\t" + "果".repeat(257)))
    }

    @Test
    fun separatorWithEmptyRightIsHandledPerSpec() {
        // 行尾 Tab 被 trim：整行退化为单词行（§3 空行/单词行口径）
        assertEquals(ParsedLine.WordOnly("apple", "apple"), LineParser.parse("apple\t"))
        // 行尾逗号不被 trim：分割右侧空 → 词行校验含逗号 → Invalid
        assertIs<ParsedLine.Invalid>(LineParser.parse("apple,   "))
    }
}
