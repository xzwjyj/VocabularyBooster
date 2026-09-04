package com.vocabularybooster.domain.model

import kotlinx.serialization.Serializable

/**
 * 播放开关（DOMAIN_MODEL §3.4 六项，FR-10）：PRONUNCIATION / SPELLING / MEANING_EN /
 * MEANING_CN / EXAMPLE / EXAMPLE_CN，默认全开（DATABASE_SCHEMA §2.11 内置键）。
 * AppSetting `settings.playbackToggles` 以此类型 JSON 序列化存储。
 */
@Serializable
public data class PlaybackToggles(
    public val pronunciation: Boolean = true,
    public val spelling: Boolean = true,
    public val meaningEn: Boolean = true,
    public val meaningCn: Boolean = true,
    public val example: Boolean = true,
    public val exampleCn: Boolean = true,
) {
    /** 六项全关 = 会话开始拒绝判据 PLAYBACK_DISABLED（LE spec §3 / TC-AE-02）。 */
    public val anyEnabled: Boolean
        get() = pronunciation || spelling || meaningEn || meaningCn || example || exampleCn

    public companion object {
        public val DEFAULT: PlaybackToggles = PlaybackToggles()
    }
}
