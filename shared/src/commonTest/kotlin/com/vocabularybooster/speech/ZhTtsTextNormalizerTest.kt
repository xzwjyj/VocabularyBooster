package com.vocabularybooster.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TC-AE-32（SCR-ZHNUM / FR-24 缺陷修复）：ZH TTS 文本数字归一化。
 * zipvoice 前端对非 CJK 词硬编码 espeak en-us（数字被英读）→ 合成前把中文段里
 * 自由数字串预转中文读法。边界表：位权分组 / 零填充 / 一十省一 / 小数逐位 /
 * 前导零逐位 / 字母守卫（MP3·3D·1990s）/ 超长防御 / legion 装机原文。
 */
class ZhTtsTextNormalizerTest {

    @Test
    fun integersConvertByPlaceValue() {
        assertEquals("零", ZhTtsTextNormalizer.normalize("0"))
        assertEquals("十", ZhTtsTextNormalizer.normalize("10"))
        assertEquals("十二", ZhTtsTextNormalizer.normalize("12"))
        assertEquals("一百", ZhTtsTextNormalizer.normalize("100"))
        assertEquals("一百零一", ZhTtsTextNormalizer.normalize("101"))
        assertEquals("一百一十", ZhTtsTextNormalizer.normalize("110"))
        assertEquals("一千", ZhTtsTextNormalizer.normalize("1000"))
        assertEquals("一千零一", ZhTtsTextNormalizer.normalize("1001"))
        assertEquals("一千零一十", ZhTtsTextNormalizer.normalize("1010"))
        assertEquals("一千一百", ZhTtsTextNormalizer.normalize("1100"))
        assertEquals("三千", ZhTtsTextNormalizer.normalize("3000"))
        assertEquals("六千", ZhTtsTextNormalizer.normalize("6000"))
        assertEquals("一万", ZhTtsTextNormalizer.normalize("10000"))
        assertEquals("一万二千", ZhTtsTextNormalizer.normalize("12000"))
        assertEquals("十万", ZhTtsTextNormalizer.normalize("100000"))
        assertEquals("十万零一十二", ZhTtsTextNormalizer.normalize("100012"))
        assertEquals("一百万", ZhTtsTextNormalizer.normalize("1000000"))
        assertEquals("一亿", ZhTtsTextNormalizer.normalize("100000000"))
        assertEquals("一亿零一万", ZhTtsTextNormalizer.normalize("100010000"))
        assertEquals("一亿零一", ZhTtsTextNormalizer.normalize("100000001"))
        assertEquals("一亿二千三百四十五万六千七百八十九", ZhTtsTextNormalizer.normalize("123456789"))
    }

    @Test
    fun decimalsConvertDigitByDigitAfterPoint() {
        assertEquals("三点一四", ZhTtsTextNormalizer.normalize("3.14"))
        assertEquals("零点五", ZhTtsTextNormalizer.normalize("0.5"))
        assertEquals("十点二五", ZhTtsTextNormalizer.normalize("10.25"))
    }

    @Test
    fun leadingZeroRunsReadDigitByDigit() {
        assertEquals("零零七", ZhTtsTextNormalizer.normalize("007"))
        assertEquals("零九点五", ZhTtsTextNormalizer.normalize("09.5"))
    }

    @Test
    fun digitsAdjacentToAsciiLettersStayUntouched() {
        assertEquals("MP3", ZhTtsTextNormalizer.normalize("MP3"))
        assertEquals("3D", ZhTtsTextNormalizer.normalize("3D"))
        assertEquals("1990s", ZhTtsTextNormalizer.normalize("1990s"))
        assertEquals("支持 MP3 播放", ZhTtsTextNormalizer.normalize("支持 MP3 播放"))
    }

    @Test
    fun overlyLongIntegersStayUntouched() {
        assertEquals("1000000000000", ZhTtsTextNormalizer.normalize("1000000000000"))
    }

    @Test
    fun freeDigitsInsideChineseSentenceConvert() {
        // 装机缺陷原文（巫师三批次 legion 中文释义，data.json）
        assertEquals(
            "（军事，古罗马）罗马军队的主要单位或师，通常由三千至六千名步兵和一百至二百名骑兵组成。",
            ZhTtsTextNormalizer.normalize(
                "（军事，古罗马）罗马军队的主要单位或师，通常由3000至6000名步兵和100至200名骑兵组成。",
            ),
        )
        assertEquals("第三千章", ZhTtsTextNormalizer.normalize("第3000章"))
    }

    @Test
    fun textWithoutDigitsReturnsUnchanged() {
        assertEquals("军团，军队，众多的人。", ZhTtsTextNormalizer.normalize("军团，军队，众多的人。"))
        assertEquals("", ZhTtsTextNormalizer.normalize(""))
    }
}
