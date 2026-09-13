package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.PlaybackToggles

/**
 * 学习设置读取端口（FR-15 / DATABASE_SCHEMA §2.11 AppSetting 内置键）。
 * 引擎只经此端口取设置（LE spec §12：不直读 SQLDelight / Android）；
 * 设置写入（设置页）属 Phase 8，本端口只读。
 * Phase 4 Step 1 裁决 L6：加法扩展 commandWindowMs / ttsRate / ttsPitch 三读取，
 * Phase 3 既有 groupSize / playbackToggles 语义完全不变，不另建第二个 Settings 端口。
 */
public interface LearningSettingsRepository {

    /** `settings.groupSize`：会话分组大小来源（LE spec §4，调用前已确定值）。 */
    public suspend fun getGroupSize(): Int

    /** `settings.playbackToggles`：六项播放开关。 */
    public suspend fun getPlaybackToggles(): PlaybackToggles

    /** `settings.commandWindowMs`：命令窗口时长（AUDIO §3；Phase 4 为纯倒计时，裁决 L1）。 */
    public suspend fun getCommandWindowMs(): Long

    /** `settings.ttsRate`：TTS 语速倍率（SpeakRequest.rate 基值，AUDIO §1 rateScale 乘算）。 */
    public suspend fun getTtsRate(): Float

    /** `settings.ttsPitch`：TTS 音调。 */
    public suspend fun getTtsPitch(): Float

    public companion object {
        /** 内置默认值（DATABASE_SCHEMA §2.11）；不进入 GroupSplitter——纯函数保持由调用方传入。 */
        public const val DEFAULT_GROUP_SIZE: Int = 10

        /** 命令窗口默认 4000ms（FR-15 / DATABASE_SCHEMA §2.11）。 */
        public const val DEFAULT_COMMAND_WINDOW_MS: Long = 4_000L

        /** TTS 语速 / 音调默认 1.0（FR-15）。 */
        public const val DEFAULT_TTS_RATE: Float = 1.0f

        public const val DEFAULT_TTS_PITCH: Float = 1.0f
    }
}
