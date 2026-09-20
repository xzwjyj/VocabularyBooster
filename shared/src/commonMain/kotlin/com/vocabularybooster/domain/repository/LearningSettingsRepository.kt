package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.Lang
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
@Suppress("TooManyFunctions") // 裁决 L6：设置全部键走本端口加法扩展，不另建第二设置端口（FR-15/FR-19）
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

    // —— Phase 8.6 TTS 音色（FR-19，L6 加法扩展）：null = 跟随系统默认（setLanguage 兜底） ——

    /**
     * `settings.ttsVoiceEn`：英语朗读音色 id。**缺键或值损坏均返回 null**（音色为设备相关数据，
     * 可因卸载自然失效——热路径防御性降级，不抛损坏异常；与其他键的损坏语义不同，特此注明）。
     */
    public suspend fun getTtsVoiceEn(): String?

    /** `settings.ttsVoiceZh`：中文朗读音色 id；语义同 [getTtsVoiceEn]。 */
    public suspend fun getTtsVoiceZh(): String?

    /** 写英语音色 id；null = 清除（跟随系统）；非空必须非空白，否则 [RepositoryValidationException]。 */
    public suspend fun setTtsVoiceEn(value: String?)

    /** 写中文音色 id；null = 清除；语义同 [setTtsVoiceEn]。 */
    public suspend fun setTtsVoiceZh(value: String?)

    /**
     * `settings.ttsAccent`：英文段朗读口音（FR-22）——EN_US（美音，缺省）/ EN_GB（英音）。
     * 只影响英文段；中文段不受影响。生效粒度 = 下一 Segment（编排器游标懒读，对齐 L4）。
     */
    public suspend fun getTtsAccent(): Lang

    /** 写口音；值只能是 [Lang.EN_US]/[Lang.EN_GB]（ZH_CN 非口音选项），否则 [RepositoryValidationException]。 */
    public suspend fun setTtsAccent(value: Lang)

    public companion object {
        /** 内置默认值（DATABASE_SCHEMA §2.11）；不进入 GroupSplitter——纯函数保持由调用方传入。 */
        public const val DEFAULT_GROUP_SIZE: Int = 10

        /** 命令窗口默认 4000ms（FR-15 / DATABASE_SCHEMA §2.11）。 */
        public const val DEFAULT_COMMAND_WINDOW_MS: Long = 4_000L

        /** TTS 语速 / 音调默认 1.0（FR-15）。 */
        public const val DEFAULT_TTS_RATE: Float = 1.0f

        public const val DEFAULT_TTS_PITCH: Float = 1.0f

        /** 发音口音默认美音（FR-22）。 */
        public val DEFAULT_TTS_ACCENT: Lang = Lang.EN_US
    }
}
