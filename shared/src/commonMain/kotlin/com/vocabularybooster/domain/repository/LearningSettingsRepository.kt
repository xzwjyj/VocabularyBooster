package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.PlaybackToggles

/**
 * 学习设置端口（FR-15 / DATABASE_SCHEMA §2.11 AppSetting 内置键）。
 * 引擎只经此端口取设置（LE spec §12：不直读 SQLDelight / Android）；
 * 写方法自 Phase 8 设置页提供（FR-15 即时持久化），读语义自 Phase 3/4 完全不变。
 * Phase 4 Step 1 裁决 L6：加法扩展 commandWindowMs / ttsRate / ttsPitch 三读取，
 * Phase 3 既有 groupSize / playbackToggles 语义完全不变，不另建第二个 Settings 端口。
 * 生效时机由既有读取点天然满足：开关/语速/音调下一 Segment、commandWindowMs 下一窗口、
 * groupSize 仅新会话（FR-6 第 4 条）——写入无失效广播。
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

    // —— Phase 8 设置页写路径（FR-15 即时持久化；校验镜像读侧，越界拒绝写入） ——

    /** 写 `settings.groupSize`；值必须 ≥ 1，否则 [RepositoryValidationException]。 */
    public suspend fun setGroupSize(value: Int)

    /**
     * 写 `settings.playbackToggles`（六项任意组合可写；全关由会话开始拒绝
     * PLAYBACK_DISABLED 裁决，LE spec §3 / TC-AE-02——写入侧不重复拦截）。
     */
    public suspend fun setPlaybackToggles(value: PlaybackToggles)

    /** 写 `settings.commandWindowMs`；值必须 > 0，否则 [RepositoryValidationException]。 */
    public suspend fun setCommandWindowMs(value: Long)

    /** 写 `settings.ttsRate`；值必须 > 0，否则 [RepositoryValidationException]。 */
    public suspend fun setTtsRate(value: Float)

    /** 写 `settings.ttsPitch`；值必须 > 0，否则 [RepositoryValidationException]。 */
    public suspend fun setTtsPitch(value: Float)

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
