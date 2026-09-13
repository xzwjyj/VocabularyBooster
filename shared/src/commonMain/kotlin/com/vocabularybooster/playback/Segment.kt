package com.vocabularybooster.playback

import com.vocabularybooster.domain.model.Lang

/**
 * 播放最小单元（AUDIO_ENGINE_SPEC §1）：一次 TTS 或文件音频的完整播放。
 * [index] 为词内序（0 起）；[owner] 供 UI 高亮联动；
 * [rateScale] 为段类型基础语速倍率（SPELLING 0.8，其余 1.0），
 * 用户 ttsRate 设置在 speak 请求组装时相乘（§3 裁决 L6）。
 */
public data class Segment(
    public val index: Int,
    public val type: SegmentType,
    public val text: String?,
    public val track: TrackDescriptor?,
    public val lang: Lang,
    public val rateScale: Float,
    public val owner: SegmentOwner,
)

/** 段类型（AUDIO_ENGINE_SPEC §1 表：来源 / 语言 / 播放方式）。 */
public enum class SegmentType { PRONUNCIATION, SPELLING, MEANING_EN, MEANING_CN, EXAMPLE_AUDIO, EXAMPLE_CN }

/** 段归属（AUDIO_ENGINE_SPEC §1 owner，UI 高亮联动）：词本身 / 释义条目 / 例句。 */
public sealed interface SegmentOwner {
    public data object Word : SegmentOwner
    public data class Definition(public val definitionEntryId: Long) : SegmentOwner
    public data class Example(public val exampleId: Long) : SegmentOwner
}
