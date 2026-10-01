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
    fun spaceThenCommaSplitsPhraseAndTranslation() {
        // v1.5 短语修正：首个空格右侧以英文开头（world,你好）→ 跳过该空格，
        // 下探逗号级分割出多词短语 + 译文
        val parsed = LineParser.parse("hello world,你好")
        val withTranslation = assertIs<ParsedLine.WordWithTranslation>(parsed)
        assertEquals("hello world", withTranslation.text)
        assertEquals("hello world", withTranslation.normalizedText)
        assertEquals("你好", withTranslation.translation)
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
        // v1.5 短语修正：单空格右侧是英文（off）→ 词内空格，整行按多词短语存活；
        // 连字符与撇号仍是词法字符
        assertEquals(
            ParsedLine.WordOnly("take off", "take off"),
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

    @Test // TC-IMP-16：多词短语行整词存活（用户实录 2026-10-01：`roll out` 曾被切成 roll + 译文 out）
    fun multiWordPhraseLineSurvivesAsSingleWord() {
        assertEquals(
            ParsedLine.WordOnly("roll out", "roll out"),
            LineParser.parse("roll out"),
        )
        assertEquals(
            ParsedLine.WordOnly("New York Times", "new york times"),
            LineParser.parse("New York Times"),
        )
    }

    @Test // TC-IMP-16：短语 + 单空格译文 → 在首个非英文开头的空格处分割
    fun phraseWithSpaceSeparatedTranslationSplitsBeforeChinese() {
        assertEquals(
            ParsedLine.WordWithTranslation("roll out", "roll out", "推出"),
            LineParser.parse("roll out 推出"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("apple pie", "apple pie", "苹果派"),
            LineParser.parse("apple pie 苹果派"),
        )
    }

    @Test // TC-IMP-16：全英文行 → 整行短语（英文译文请用 Tab/逗号显式分隔）
    fun allEnglishLineWithNoTranslationCandidateBecomesPhrase() {
        assertEquals(
            ParsedLine.WordOnly("apple red apple", "apple red apple"),
            LineParser.parse("apple red apple"),
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

    @Test
    fun posMarkerIsStrippedAndCanonicalized() {
        // vt./vi. 均映射 verb（长词形优先，v. 不吞 vt.）；n./adj./adv./int. 各归规范名
        assertEquals(
            ParsedLine.WordWithTranslation("hamper", "hamper", "妨碍", "verb"),
            LineParser.parse("hamper\tvt. 妨碍"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("adapt", "adapt", "适应", "verb"),
            LineParser.parse("adapt\tvi.适应"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("run", "run", "跑", "verb"),
            LineParser.parse("run\tv. 跑"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("hamper", "hamper", "（带盖的）大篮子", "noun"),
            LineParser.parse("hamper\tn.（带盖的）大篮子"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("calm", "calm", "平静的", "adjective"),
            LineParser.parse("calm\tadj. 平静的"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("quickly", "quickly", "迅速地", "adverb"),
            LineParser.parse("quickly\tadv. 迅速地"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("wow", "wow", "哇", "interjection"),
            LineParser.parse("wow\tint. 哇"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("about", "about", "关于", "preposition"),
            LineParser.parse("about\tprep. 关于"),
        )
    }

    @Test
    fun posMarkerOnlyLineYieldsNullTranslation() {
        // 标记独占行：等效裸词 + 词性过滤（无译文供 SenseMatcher）
        assertEquals(
            ParsedLine.WordWithTranslation("hamper", "hamper", null, "noun"),
            LineParser.parse("hamper\tn."),
        )
        // 大小写不敏感
        assertEquals(
            ParsedLine.WordWithTranslation("hamper", "hamper", null, "noun"),
            LineParser.parse("hamper\tN."),
        )
    }

    @Test
    fun unrecognizedOrDotlessMarkerStaysPlainTranslation() {
        // 词表未收纳的标记（conj. 等）与无点号前缀：整段原样保留、不误判词性
        assertEquals(
            ParsedLine.WordWithTranslation("and", "and", "conj. 并且", null),
            LineParser.parse("and\tconj. 并且"),
        )
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "adj 是", null),
            LineParser.parse("apple\tadj 是"),
        )
        // 译文以「非标记词 + 点」开头不受影响（n 后必须是点才算标记）
        assertEquals(
            ParsedLine.WordWithTranslation("apple", "apple", "no. 5 之类", null),
            LineParser.parse("apple\tno. 5 之类"),
        )
    }
}
