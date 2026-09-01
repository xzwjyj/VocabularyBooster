package com.vocabularybooster.domain.model

/**
 * 播放语言（AUDIO_ENGINE_SPEC §1：TTS 每段必须正确切换语言，双语段切换是硬性要求）。
 */
public enum class Lang(public val tag: String) {
    EN_US("en-US"),
    ZH_CN("zh-CN"),
}
