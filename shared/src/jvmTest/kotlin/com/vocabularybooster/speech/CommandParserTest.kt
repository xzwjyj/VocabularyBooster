package com.vocabularybooster.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CommandParser 测试（AUDIO_ENGINE_SPEC §7 / TEST_PLAN TC-AE-10）：
 * 别名 / 大小写 / 全角 / 带标点 / 分词空格（Vosk 中文按字分词）/ 近音兜底白名单 /
 * 首/末字近音结构规则（v2.0：首字≈会 或 末字≈了 → 命中）→ MASTERED；
 * 未知（含空文本、繁体、未启用、首尾字均不在近音集的无关短语）→ UNKNOWN。
 * 精确匹配 + 近音白名单 + 结构规则（无任意模糊 NLP）；partial 结果不进解析（端口契约，编排器侧不送入）。
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

    // —— 空白（§7 全角空格折叠 + 去全部空白）——

    @Test
    fun surroundingWhitespaceIgnored() {
        assertEquals(VoiceCommand.MASTERED, parser.parse(" 会了 ", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("　会了　", aliases, enabled)) // U+3000 全角空格
    }

    // —— 内部空白（§7 去除全部空白：Vosk 中文按字分词伪影，2026-09-14 vivo 实测「会 了」被吞）——

    @Test
    fun interWordWhitespaceIsNormalized() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("会 了", aliases, enabled)) // vivo 实测字符串
        assertEquals(VoiceCommand.MASTERED, parser.parse("记 住 了", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("掌 握了", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("　会　了　", aliases, enabled)) // 全角空格（内部+首尾）
        assertEquals(VoiceCommand.MASTERED, parser.parse("会 了。", aliases, enabled)) // 分词空格 + 末尾标点
        assertEquals(VoiceCommand.MASTERED, parser.parse("  会   了 ", aliases, enabled)) // 多空格混合
    }

    @Test
    fun latinAliasWithSpacesMatchesAfterWhitespaceRemoval() {
        // 别名与识别文本走同一归一化（双侧去空白后精确等值，语义不因去空白放宽）
        val latin = setOf("Got it")
        assertEquals(VoiceCommand.MASTERED, parser.parse("got  it", latin, enabled)) // 双空格
        assertEquals(VoiceCommand.MASTERED, parser.parse("g o t i t", latin, enabled)) // 逐字分词
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

    // —— 近音兜底白名单（§7 v1.7：Vosk 近音误听，2026-09-16 vivo 实测「会了」→「坏了」）——

    @Test
    fun nearHomophonesOfMasteredAliasHit() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("坏了", aliases, enabled)) // vivo 实测误听
        assertEquals(VoiceCommand.MASTERED, parser.parse("换了", aliases, enabled)) // 近音 huàn le
        assertEquals(VoiceCommand.MASTERED, parser.parse("会啦", aliases, enabled)) // 语气词变体
        assertEquals(VoiceCommand.MASTERED, parser.parse("回来", aliases, enabled)) // vivo 实测 07:23「会了」→「回来」
        assertEquals(VoiceCommand.MASTERED, parser.parse("会", aliases, enabled)) // vivo 实测 07:37 断字截断（「了」被丢）
        assertEquals(VoiceCommand.MASTERED, parser.parse("回 了", aliases, enabled)) // 分词伪影 + 近音
        assertEquals(VoiceCommand.MASTERED, parser.parse("会来", aliases, enabled)) // 同族 huì-lái
        assertEquals(VoiceCommand.MASTERED, parser.parse("坏 了", aliases, enabled)) // 分词伪影 + 近音
        assertEquals(VoiceCommand.MASTERED, parser.parse("了 了", aliases, enabled)) // vivo 实测 23:26「会了」→「了了」（h 声母丢失，boost 窗口）
        assertEquals(VoiceCommand.MASTERED, parser.parse("记住啦", aliases, enabled))
        assertEquals(VoiceCommand.MASTERED, parser.parse("掌握咯", aliases, enabled))
    }

    @Test
    fun nearHomophonesDoNotLeakIntoUnrelatedPhrases() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("你好", aliases, enabled)) // 无关短语不误杀（D5）：你∉会近音集、好∉了近音集
    }

    @Test
    fun nearHomophonesAreScopedToDefaultAliases() {
        // 白名单按别名归集：自定义别名集无「会了」近音兜底（Phase 8 随别名设置化一并扩展）
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("坏了", setOf("Got it"), enabled))
    }

    // —— 首/末字近音结构规则（§7 v2.0，用户裁决 2026-09-17：首字≈会 或 末字≈了 → 命中「会了」）——

    @Test
    fun headCharNearHuiHits() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("坏", aliases, enabled)) // 单字残片：首字坏（原 UNKNOWN 守卫，随裁决翻转）
        assertEquals(VoiceCommand.MASTERED, parser.parse("换", aliases, enabled)) // 单字残片：首字换
        assertEquals(VoiceCommand.MASTERED, parser.parse("汇啊", aliases, enabled)) // 首字近音 + 任意尾字
        assertEquals(VoiceCommand.MASTERED, parser.parse("回呀", aliases, enabled))
    }

    @Test
    fun tailCharNearLeHits() {
        assertEquals(VoiceCommand.MASTERED, parser.parse("了", aliases, enabled)) // 单字：末字了
        assertEquals(VoiceCommand.MASTERED, parser.parse("了 了", aliases, enabled)) // vivo 实测 23:26 h 声母丢失
        assertEquals(VoiceCommand.MASTERED, parser.parse("会了吗", aliases, enabled)) // 原疑问形式守卫，随裁决翻转（首字会）
        assertEquals(VoiceCommand.MASTERED, parser.parse("我觉得这个词已经会了", aliases, enabled)) // 原非精确守卫，随裁决翻转（末字了）
        assertEquals(VoiceCommand.MASTERED, parser.parse("會了", aliases, enabled)) // 繁体也兜不住：規則只看首/末字，末字了命中（原繁体守卫随裁决失效）
    }

    @Test
    fun structuralRuleStillRejectsUnrelated() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("你好", aliases, enabled)) // 首尾字均不在近音集
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("hello", aliases, enabled))
    }

    @Test
    fun structuralRuleIsScopedToHuiLeAlias() {
        // 结构规则同白名单按别名归集：自定义别名集不适用
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("坏", setOf("Got it"), enabled))
    }

    // —— 未命中 → UNKNOWN（不误触发，TC-AE-10 未知行 / §9 不误杀）——

    @Test
    fun unrelatedTextIsUnknown() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("你好", aliases, enabled))
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("hello", aliases, enabled))
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
        // §7 只忽略「末尾」标点：前导标点不剥 → 整串不再精确等值。中文「，会了」经结构规则末字「了」
        // 仍命中（v2.0 裁决，见 tailCharNearLeHits）；纯精确语义（无结构规则的别名）用拉丁别名断言。
        assertEquals(VoiceCommand.MASTERED, parser.parse("，会了", aliases, enabled)) // 前导标点 + 末字了 → 结构命中
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("，got it", setOf("Got it"), enabled)) // 前导标点阻断精确等值
    }

    @Test
    fun disabledCommandNeverHits() {
        assertEquals(VoiceCommand.UNKNOWN, parser.parse("会了", aliases, enabled = emptySet()))
    }
}
