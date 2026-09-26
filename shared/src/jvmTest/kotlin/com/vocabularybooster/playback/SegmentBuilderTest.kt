package com.vocabularybooster.playback

import com.vocabularybooster.domain.model.DefinitionEntry
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SegmentBuilder 纯函数测试（AUDIO_ENGINE_SPEC §2，TC-AE-01/02）：
 * 六开关全开 → I-8 结构段序（POS 排序 + exampleOrder）；导入词 → 仅 PRONUNCIATION + SPELLING；
 * 开关组合 → 映射过滤；全关 → 空（会话级拒绝由学习引擎负责，LE spec §3）。
 */
class SegmentBuilderTest {

    private val word = Word(wordId = 1L, text = "booster", normalizedText = "booster")

    // 乱序插入：noun(order 0) 在前提交、verb(order 1) 后提交 → 构建必须按 (posOrder, defOrder) 纠正
    private val verbDef = DefinitionEntry(11L, 1L, "v", partOfSpeechOrder = 1, definitionOrder = 0, meaningEN = "to push up", meaningCN = "推进")
    private val nounDef = DefinitionEntry(12L, 1L, "n", partOfSpeechOrder = 0, definitionOrder = 0, meaningEN = "a device", meaningCN = "助推器")
    private val exampleA = Example(
        exampleId = 101L, definitionEntryId = 12L, sentence = "a booster seat", chineseTranslation = "增高垫",
        sourceType = ExampleSourceType.TTS, audioUri = "https://audio/a.mp3", audioDurationMs = 900L, exampleOrder = 5,
    )
    private val exampleB = Example(
        exampleId = 102L, definitionEntryId = 12L, sentence = "a booster rocket", chineseTranslation = "助推火箭",
        sourceType = ExampleSourceType.TTS, audioUri = null, exampleOrder = 1,
    )

    private fun content(
        definitions: List<DefinitionEntry>,
        examples: Map<Long, List<Example>>,
    ) = PlaybackContent(word = word, selectedDefinitions = definitions, examplesByDefinitionEntryId = examples)

    @Test
    fun fullTogglesProduceCanonicalSegmentSequenceAndMetadata() {
        // noun(exampleOrder 1,5 乱序存入) 先于 verb；PRON/SPELL 打头；字段逐项断言
        val segments = SegmentBuilder.buildSegments(
            content(listOf(verbDef, nounDef), mapOf(12L to listOf(exampleA, exampleB))),
            PlaybackToggles.DEFAULT,
        )

        val expected = listOf(
            Triple(SegmentType.PRONUNCIATION, "booster", Lang.EN_US),
            Triple(SegmentType.SPELLING, "b, o, o, s, t, e, r", Lang.EN_US),
            Triple(SegmentType.MEANING_EN, "a device", Lang.EN_US),
            Triple(SegmentType.MEANING_CN, "助推器", Lang.ZH_CN),
            Triple(SegmentType.EXAMPLE_AUDIO, "a booster rocket", Lang.EN_US),
            Triple(SegmentType.EXAMPLE_CN, "助推火箭", Lang.ZH_CN),
            Triple(SegmentType.EXAMPLE_AUDIO, "a booster seat", Lang.EN_US),
            Triple(SegmentType.EXAMPLE_CN, "增高垫", Lang.ZH_CN),
            Triple(SegmentType.MEANING_EN, "to push up", Lang.EN_US),
            Triple(SegmentType.MEANING_CN, "推进", Lang.ZH_CN),
        )
        assertEquals(expected.map { it.first }, segments.map { it.type })
        assertEquals(expected.map { it.second }, segments.map { it.text })
        assertEquals(expected.map { it.third }, segments.map { it.lang })
        assertEquals((0 until segments.size).toList(), segments.map { it.index })
        // SPELLING 0.8，其余 1.0（§1）
        assertEquals(segments.map { if (it.type == SegmentType.SPELLING) 0.8f else 1.0f }, segments.map { it.rateScale })
        assertEquals(SegmentOwner.Word, segments[0].owner)
        assertEquals(SegmentOwner.Definition(12L), segments[2].owner)
        assertEquals(SegmentOwner.Example(102L), segments[4].owner)
        assertEquals(TrackDescriptor("ex-101", "https://audio/a.mp3", 900L), segments[6].track) // exampleA(order 5, 有 audioUri) → 文件段
        assertNull(segments[4].track) // 无 audioUri → TTS 段（无 track，非降级）
        assertNull(segments[0].track)
    }

    @Test
    fun importedWordWithoutSelectionsYieldsPronunciationAndSpellingOnly() {
        // TC-AE-01：导入词（无选中释义/例句）→ 仅 PRONUNCIATION + SPELLING（LE §10-3 播放侧）
        val segments = SegmentBuilder.buildSegments(content(emptyList(), emptyMap()), PlaybackToggles.DEFAULT)

        assertEquals(listOf(SegmentType.PRONUNCIATION, SegmentType.SPELLING), segments.map { it.type })
        assertEquals(listOf(SegmentOwner.Word, SegmentOwner.Word), segments.map { it.owner })
    }

    @Test
    fun toggleCombinationsFilterSequencePerMapping() {
        // TC-AE-02：开关组合 → 段序列与 §1 映射表一致
        val content = content(listOf(nounDef), mapOf(12L to listOf(exampleB)))
        val onlyCn = PlaybackToggles(
            pronunciation = false, spelling = false, meaningEn = false,
            meaningCn = true, example = false, exampleCn = true,
        )
        // exampleCn 独立于 example 门控 EXAMPLE_CN（§1 映射表）
        assertEquals(
            listOf(SegmentType.MEANING_CN, SegmentType.EXAMPLE_CN),
            SegmentBuilder.buildSegments(content, onlyCn).map { it.type },
        )

        val examplesOnly = PlaybackToggles(
            pronunciation = false, spelling = false, meaningEn = false,
            meaningCn = false, example = true, exampleCn = true,
        )
        assertEquals(
            listOf(SegmentType.EXAMPLE_AUDIO, SegmentType.EXAMPLE_CN),
            SegmentBuilder.buildSegments(content, examplesOnly).map { it.type },
        )
    }

    @Test
    fun allTogglesOffYieldsEmptySegments() {
        // TC-AE-02 后半：全关 → 空（「学习引擎拒绝开始会话」由 DefaultLearningEngineIntegrationTest 锁定）
        val allOff = PlaybackToggles(
            pronunciation = false, spelling = false, meaningEn = false,
            meaningCn = false, example = false, exampleCn = false,
        )
        assertTrue(SegmentBuilder.buildSegments(content(listOf(nounDef), emptyMap()), allOff).isEmpty())
    }

    // ---- 口音映射（FR-22：规格存中性 EN_US，段消费时按口音重写）----

    @Test
    fun withEnglishAccentRewritesEnglishSegmentsOnly() {
        val specs = SegmentBuilder.buildSpecs(
            content(listOf(nounDef), mapOf(12L to listOf(exampleB))),
        )
        val mapped = specs.map { it.withEnglishAccent(Lang.EN_GB) }

        // 英文段（PRON/SPELL/MEANING_EN/EXAMPLE_AUDIO）→ EN_GB；中文段原样
        assertEquals(
            listOf(Lang.EN_GB, Lang.EN_GB, Lang.EN_GB, Lang.ZH_CN, Lang.EN_GB, Lang.ZH_CN),
            mapped.map { it.lang },
        )
        // 除 lang 外字段（type/text/track/rateScale/owner）零改动
        specs.zip(mapped).forEach { (base, accented) ->
            assertEquals(base.copy(lang = accented.lang), accented)
        }
    }

    @Test
    fun withEnglishAccentDefaultsToEnUsAndLeavesChineseUntouched() {
        val specs = SegmentBuilder.buildSpecs(content(listOf(nounDef), mapOf(12L to listOf(exampleB))))
        // 缺省美音：原样（copy 语义也全等）
        assertEquals(specs, specs.map { it.withEnglishAccent(Lang.EN_US) })
        // 防御：ZH_CN 非口音选项 → 英文段原样（不产生中文朗读英文段）
        assertEquals(specs, specs.map { it.withEnglishAccent(Lang.ZH_CN) })
    }
}
