package com.vocabularybooster.playback

import com.vocabularybooster.domain.model.DefinitionEntry
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.Word

/**
 * SegmentBuilder（AUDIO_ENGINE_SPEC §2，纯函数）：
 * 词 + 选中释义/例句 + 开关 → 词内播放分段序列。
 * 释义序 = (partOfSpeechOrder, definitionOrder)（铁律 3），例句序 = exampleOrder——
 * 两者由 Q4/Q4b 在数据层 ORDER BY 保证，本函数仍防御性排序（纯函数不信任输入顺序）。
 * 逐词空结果（如开关全关）的学习侧语义见 §2 裁决 L2：编排器直通 CommandWindow，绝不 MASTERED。
 */
public object SegmentBuilder {

    /** 六开关过滤后的完整分段序列（TC-AE-01/02）：空结果（全关）由学习引擎在会话开始拒绝。 */
    public fun buildSegments(content: PlaybackContent, toggles: PlaybackToggles): List<Segment> =
        buildSpecs(content)
            .filter { toggles.enables(it.type) }
            .mapIndexed { index, spec -> spec.toSegment(index) }

    /** 分段规格（未应用开关、未编号）：编排器按游标懒评估，实现 L4「下一 Segment 重读开关」。 */
    internal fun buildSpecs(content: PlaybackContent): List<SegmentSpec> {
        val specs = mutableListOf<SegmentSpec>()
        specs += wordSpecs(content.word)
        val definitions = content.selectedDefinitions
            .sortedWith(compareBy({ it.partOfSpeechOrder }, { it.definitionOrder }))
        for (definition in definitions) {
            val examples = content.examplesByDefinitionEntryId[definition.definitionEntryId].orEmpty()
            specs += definitionSpecs(definition, examples)
        }
        return specs
    }

    /** 词级打头段：PRONUNCIATION + SPELLING（§1 表）。 */
    private fun wordSpecs(word: Word): List<SegmentSpec> = listOf(
        SegmentSpec(
            type = SegmentType.PRONUNCIATION, text = word.text, track = null,
            lang = Lang.EN_US, rateScale = BASE_RATE_SCALE, owner = SegmentOwner.Word,
        ),
        SegmentSpec(
            type = SegmentType.SPELLING, text = spellingText(word.text), track = null,
            lang = Lang.EN_US, rateScale = SPELLING_RATE_SCALE, owner = SegmentOwner.Word,
        ),
    )

    /** 释义段对（EN 先于 CN）+ 逐例句段对（例句序 = exampleOrder，防御性排序）。 */
    private fun definitionSpecs(definition: DefinitionEntry, examples: List<Example>): List<SegmentSpec> {
        val defId = definition.definitionEntryId
        val specs = mutableListOf(
            SegmentSpec(
                type = SegmentType.MEANING_EN, text = definition.meaningEN, track = null,
                lang = Lang.EN_US, rateScale = BASE_RATE_SCALE, owner = SegmentOwner.Definition(defId),
            ),
            SegmentSpec(
                type = SegmentType.MEANING_CN, text = definition.meaningCN, track = null,
                lang = Lang.ZH_CN, rateScale = BASE_RATE_SCALE, owner = SegmentOwner.Definition(defId),
            ),
        )
        for (example in examples.sortedBy { it.exampleOrder }) {
            specs += SegmentSpec(
                type = SegmentType.EXAMPLE_AUDIO, text = example.sentence,
                track = example.audioUri?.let { uri -> exampleTrack(example, uri) },
                lang = Lang.EN_US, rateScale = BASE_RATE_SCALE, owner = SegmentOwner.Example(example.exampleId),
            )
            specs += SegmentSpec(
                type = SegmentType.EXAMPLE_CN, text = example.chineseTranslation, track = null,
                lang = Lang.ZH_CN, rateScale = BASE_RATE_SCALE, owner = SegmentOwner.Example(example.exampleId),
            )
        }
        return specs
    }

    /** 例句文件段描述符：有 audioUri 才有 track（无 = TTS 段，非降级）。 */
    private fun exampleTrack(example: Example, uri: String): TrackDescriptor = TrackDescriptor(
        trackId = "ex-${example.exampleId}",
        audioUri = uri,
        durationMs = example.audioDurationMs,
    )

    /**
     * SPELLING 逐字母文本：`booster` → `b, o, o, s, t, e, r`（AUDIO_ENGINE_SPEC §1 表）。
     * 逗号分隔（SCR-SPELLPAUSE）：未实现逐字母拼接的渲染路径靠逗号获得自然停顿；
     * 神经/系统 actual 按 `", "` 拆字母逐个合成 + 显式静音（时长见 settings.spellingPauseMs）。
     */
    private fun spellingText(text: String): String = text.lowercase().map { "$it" }.joinToString(", ")

    private const val BASE_RATE_SCALE: Float = 1.0f
    private const val SPELLING_RATE_SCALE: Float = 0.8f
}

/** 开关对段类型的门控（AUDIO_ENGINE_SPEC §1/§2 映射表）。 */
internal fun PlaybackToggles.enables(type: SegmentType): Boolean = when (type) {
    SegmentType.PRONUNCIATION -> pronunciation
    SegmentType.SPELLING -> spelling
    SegmentType.MEANING_EN -> meaningEn
    SegmentType.MEANING_CN -> meaningCn
    SegmentType.EXAMPLE_AUDIO -> example
    SegmentType.EXAMPLE_CN -> exampleCn
}

/**
 * FR-22 口音映射：英文段基语言（EN_US）按口音设置重写为 EN_US/EN_GB；中文段原样。
 * 规格恒存中性基语言，编排器游标逐段应用——口音生效粒度 = 下一 Segment（对齐 L4 开关懒读）。
 * [accent] 只接受英语变体（ZH_CN 非口音选项，防御性原样返回）。
 */
internal fun SegmentSpec.withEnglishAccent(accent: Lang): SegmentSpec =
    if (lang == Lang.EN_US && accent != Lang.ZH_CN) copy(lang = accent) else this

/** 分段规格：应用开关与编号前的原材料（[SegmentBuilder.buildSpecs] 输出）。 */
internal data class SegmentSpec(
    val type: SegmentType,
    val text: String?,
    val track: TrackDescriptor?,
    val lang: Lang,
    val rateScale: Float,
    val owner: SegmentOwner,
) {
    fun toSegment(index: Int): Segment = Segment(index, type, text, track, lang, rateScale, owner)
}
