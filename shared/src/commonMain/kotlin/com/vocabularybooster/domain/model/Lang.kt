package com.vocabularybooster.domain.model

/**
 * 播放语言（AUDIO_ENGINE_SPEC §1：TTS 每段必须正确切换语言，双语段切换是硬性要求）。
 * EN_GB 为 FR-22 发音口音新增：仅作英文段朗读口音映射目标（`settings.ttsAccent`），
 * SegmentBuilder 规格恒存中性基语言 EN_US，口音在段消费时映射（下一 Segment 生效，对齐 L4）。
 */
public enum class Lang(public val tag: String) {
    EN_US("en-US"),
    EN_GB("en-GB"),
    ZH_CN("zh-CN"),
}
