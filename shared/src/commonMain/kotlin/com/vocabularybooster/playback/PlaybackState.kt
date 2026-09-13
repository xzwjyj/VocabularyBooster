package com.vocabularybooster.playback

import com.vocabularybooster.learning.WordRef

/**
 * 编排器对外唯一播放状态源（AUDIO_ENGINE_SPEC §3 状态机）：
 * Idle │ Playing(pos) │ Paused(pos) │ CommandWindow(wordRef, remainingMs) │ Completed │ Stopped。
 * 单一 StateFlow 暴露（含当前段、进度、命令窗口倒计时、降级标志），不设第二套平行播放状态。
 */
public sealed interface PlaybackState {

    /** 未接入会话。 */
    public data object Idle : PlaybackState

    /**
     * 播放中。pos = (wordRef, segmentIndex, offsetMs)（§3；offsetMs 仅文件音频段有意义）。
     * [segment] 为当前段（末段完成后 300ms guard 间隙内保持为该段）；[degraded] =
     * 当前段已发生「例句音频失败 → TTS 兜底」降级（§3 降级标志，TC-AE-12）。
     */
    public data class Playing(
        public val wordRef: WordRef,
        public val wordText: String,
        public val segmentIndex: Int,
        public val segment: Segment?,
        public val offsetMs: Long,
        public val degraded: Boolean,
    ) : PlaybackState

    /**
     * 暂停。[atCommandWindow] = §3「Paused(窗口位)」（恢复重开整窗）；
     * TTS 段恢复重读本段、文件段 [offsetMs] 精确续播（FR-11 / ADR-09，绝不整词重播）。
     * [error] = TTS 失败进入的暂停（§9 可表达子集；恢复引导 UI 属后续 Step）。
     */
    public data class Paused(
        public val wordRef: WordRef,
        public val wordText: String,
        public val segmentIndex: Int,
        public val offsetMs: Long,
        public val atCommandWindow: Boolean,
        public val error: PlaybackError?,
    ) : PlaybackState

    /**
     * 命令窗口：remainingMs 随倒计时递减。[listening] = 窗口处于**语音监听模式**（识别器可用
     * 且未被降级——Phase 5 Step 1 裁决 D2）；false = 纯倒计时（P4 形态 / §9「识别引擎不可用」
     * 降级 / 窗口中途 Unavailable）。UI 只依据本字段展示监听指示，不得以计时器猜测录音状态。
     */
    public data class CommandWindow(
        public val wordRef: WordRef,
        public val remainingMs: Long,
        public val totalMs: Long,
        public val listening: Boolean,
    ) : PlaybackState

    /** 书完成（先停播放端口再进入，FR-8 / TC-AE-14）。 */
    public data object Completed : PlaybackState

    /** 用户退出（exitSession 已裁决，派生/完成结果由 [com.vocabularybooster.learning.ExitResult] 返回）。 */
    public data object Stopped : PlaybackState
}

/** TTS 失败（含音频降级后 TTS 兜底再失败；AUDIO §9 完整恢复流属后续 Phase）。 */
public enum class PlaybackError { TTS_FAILED }
