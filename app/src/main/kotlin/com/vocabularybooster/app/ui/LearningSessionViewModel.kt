package com.vocabularybooster.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.playback.PlaybackState
import com.vocabularybooster.playback.SegmentType
import kotlinx.coroutines.launch

/**
 * 正式学习会话 ViewModel（Phase 4 Step 4）：
 * 唯一职责 = 会话接入（start/resume）+ PlaybackState → immutable UI state 映射 +
 * UI action → PlaybackOrchestrator 命令转发 + 一次性会话级结果（冲突/拒绝/BOOK_DELETED）呈现。
 *
 * - 播放、TTS、分段、掌握、推进、位置持久化、CommandWindow 时序全部在编排器/引擎——本类零播放逻辑
 *   （架构方向：UI → ViewModel → PlaybackOrchestrator → ports）。
 * - CommandWindow 倒计时直接消费编排器状态（100ms tick），本类不自建计时器、不裁决推进。
 * - BOOK_DELETED：resumeSession 被拒的既有语义（TC-AE-15）——只做提示 + 退出导航，不改底层。
 * - 编排器为应用级单例，本 VM 可跨进入复用：[start] 以编排器当前状态守卫，幂等重入。
 */
@Suppress("TooManyFunctions") // 每个公开函数 = 规格定义的一个 UI 入口（§6 五控制 + 冲突三分支 + 生命周期 + 状态映射）
class LearningSessionViewModel(
    private val orchestrator: PlaybackOrchestrator,
) : ViewModel() {

    /** 学习屏 UI 状态（PlaybackState 的 app 层 immutable 投影）。 */
    var ui by mutableStateOf<LearningUiState>(LearningUiState.Loading)
        private set

    /** ACTIVE_SESSION_EXISTS 冲突（LE spec §3）：引导「恢复」或「放弃旧的」。 */
    var conflictSessionId by mutableStateOf<Long?>(null)
        private set

    /** 一次性终态/失败提示（对话框呈现后经 [consumeNotice] 清除）。 */
    var notice by mutableStateOf<LearningNotice?>(null)
        private set

    /** exit 完成结果（编排器已确认 Stopped；携带派生本 ID 供 UI 文案）。 */
    var exitResult by mutableStateOf<ExitResult?>(null)
        private set

    private var pendingBookId: Long? = null
    private var startInFlight: Boolean = false
    private var suppressingUiUpdates: Boolean = false

    /**
     * 本屏是否已接管会话：只有观察到过传输态（Playing/Paused/CommandWindow）后才开始映射状态。
     * 编排器是应用级单例——上一屏退出后遗留的 Stopped/Completed 不能被新屏当作"本次退出"消费
     * （否则学习屏一进入即被 Stopped→onExit 闪退回上一层，而 start 的新会话照常在后台播放）。
     */
    private var sessionOwned: Boolean = false

    /** 展示连续性：最近一次携带词文本的状态（Playing/Paused/Error）；CommandWindow 映射消费。 */
    private var wordTextCache: String? = null

    init {
        viewModelScope.launch {
            orchestrator.state.collect { state ->
                if (state.isTransportActive()) sessionOwned = true
                if (!suppressingUiUpdates && sessionOwned) ui = mapToUiState(state)
            }
        }
    }

    /**
     * 进入学习屏（生词本 → 开始学习）：委托编排器 startSession。
     * 幂等守卫：编排器传输态活跃（Playing/Paused/CommandWindow）时跳过——
     * 覆盖配置变更/重组的重放（会话继续）；终态（Idle/Stopped/Completed）重新开始。
     */
    @Suppress("TooGenericExceptionCaught") // UI 边界：任何编排器失败一律转 notice 呈现，不向 Compose 传播
    fun start(wordBookId: Long) {
        if (startInFlight || orchestrator.state.value.isTransportActive()) return
        startInFlight = true
        resetTransientState()
        ui = LearningUiState.Loading
        viewModelScope.launch {
            try {
                when (val result = orchestrator.startSession(wordBookId)) {
                    is StartResult.Started -> Unit // 起播由编排器状态流驱动
                    is StartResult.Rejected -> handleStartRejection(result.reason, pending = wordBookId)
                }
            } catch (e: Exception) {
                notice = LearningNotice.StartError(e.message)
            } finally {
                startInFlight = false
            }
        }
    }

    // —— 冲突裁决（ACTIVE_SESSION_EXISTS，LE spec §3 UI 引导）——

    /** 冲突对话框「恢复」：恢复既有 ACTIVE 会话（BOOK_DELETED 等拒绝经 [notice] 呈现）。 */
    @Suppress("TooGenericExceptionCaught") // 同 [start]：UI 边界宽捕获转 notice
    fun resumeExistingSession() {
        val sessionId = conflictSessionId ?: return
        conflictSessionId = null
        ui = LearningUiState.Loading
        viewModelScope.launch {
            try {
                when (val result = orchestrator.resumeSession(sessionId)) {
                    is ResumeResult.Resumed -> Unit // 恢复起播由编排器状态流驱动
                    is ResumeResult.Rejected -> notice = result.reason.toNotice()
                }
            } catch (e: Exception) {
                notice = LearningNotice.StartError(e.message)
            }
        }
    }

    /**
     * 冲突对话框「放弃旧的并开始」：resume → exit（既有退出三分支裁决，含分支 B 派生）→
     * 重新 start。全程公开编排器 API；期间 UI 抑制为 Loading（无中途播放闪烁）。
     */
    fun abandonExistingAndStart() {
        val sessionId = conflictSessionId ?: return
        val target = pendingBookId ?: return
        conflictSessionId = null
        ui = LearningUiState.Loading
        viewModelScope.launch {
            suppressingUiUpdates = true
            try {
                runCatching { orchestrator.resumeSession(sessionId) }
                runCatching { orchestrator.exit() }
            } finally {
                suppressingUiUpdates = false
            }
            start(target)
        }
    }

    /** 冲突对话框「取消」：仅清冲突态，导航返回由 Screen 直接执行。 */
    fun dismissConflict() {
        conflictSessionId = null
        pendingBookId = null
    }

    // —— 传输控制（AUDIO §6 五控制，全部经编排器；不触碰 AudioPlayer/SpeechSynthesizer）——

    fun pause() = launchCommand { orchestrator.pause() }

    fun resume() = launchCommand { orchestrator.resume() }

    fun next() = launchCommand { orchestrator.next() }

    fun replay() = launchCommand { orchestrator.replay() }

    /**
     * 「会了」按钮（FR-7 / NFR-8，Phase 5 Step 1 裁决 D2）：与语音命令汇合于编排器
     * `masterCurrentWord()`（唯一执行路径；非 CommandWindow 态编排器幂等 no-op）。
     * 本类零掌握逻辑——按钮可用性由 Screen 按 UI 态渲染，语义裁决全在编排器/引擎。
     */
    fun masterCurrentWord() = launchCommand { orchestrator.masterCurrentWord() }

    /** Exit（二次确认弹窗在 Screen 层）：调用后等待编排器状态变化（Stopped），结果存 [exitResult]。 */
    @Suppress("TooGenericExceptionCaught") // 同 [start]：UI 边界宽捕获转 notice
    fun exit() {
        viewModelScope.launch {
            try {
                exitResult = orchestrator.exit()
            } catch (e: Exception) {
                notice = LearningNotice.CommandError(e.message)
            }
        }
    }

    fun consumeNotice() {
        notice = null
    }

    /** 宿主销毁：停驱动 Job 与播放端口（编排器既有 dispose 语义，不动会话状态）。 */
    override fun onCleared() {
        orchestrator.dispose()
    }

    // —— 内部 ——

    @Suppress("TooGenericExceptionCaught") // 同 [start]：UI 边界宽捕获转 notice（五控制共用通道）
    private fun launchCommand(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                notice = LearningNotice.CommandError(e.message)
            }
        }
    }

    private fun resetTransientState() {
        conflictSessionId = null
        notice = null
        exitResult = null
        pendingBookId = null
        wordTextCache = null
    }

    /** PlaybackState → UI 投影（词文本缓存随携带态刷新，供 CommandWindow 显示延续）。 */
    private fun mapToUiState(state: PlaybackState): LearningUiState {
        when (state) {
            is PlaybackState.Playing -> wordTextCache = state.wordText
            is PlaybackState.Paused -> wordTextCache = state.wordText
            else -> Unit
        }
        return state.toUiState(wordTextCache)
    }

    /** startSession 被拒（LE spec §3 拒绝）：冲突进对话框，其余经 [notice] 引导后返回。 */
    private fun handleStartRejection(reason: StartResult.Reason, pending: Long) {
        when (reason) {
            is StartResult.Reason.ACTIVE_SESSION_EXISTS -> {
                pendingBookId = pending
                conflictSessionId = reason.sessionId
            }
            StartResult.Reason.EMPTY_BOOK ->
                notice = LearningNotice.StartRejected("生词本内没有词条，无法开始学习")
            StartResult.Reason.PLAYBACK_DISABLED ->
                notice = LearningNotice.StartRejected("播放开关全部关闭，无法开始学习")
        }
    }
}

/** 一次性会话级结果/失败（Screen 以 AlertDialog 呈现，确认后导航离开）。 */
sealed interface LearningNotice {
    /** 开始被拒（空本/全掌握/全关）——明确引导。 */
    data class StartRejected(val message: String) : LearningNotice

    /** resume 被拒：书已删（TC-AE-15）——停止学习流程，返回上一层。 */
    data object BookDeleted : LearningNotice

    /** 会话不存在/已结束。 */
    data object SessionUnavailable : LearningNotice

    data class StartError(val message: String?) : LearningNotice

    data class CommandError(val message: String?) : LearningNotice
}

private fun ResumeResult.Reason.toNotice(): LearningNotice = when (this) {
    ResumeResult.Reason.BOOK_DELETED -> LearningNotice.BookDeleted
    ResumeResult.Reason.SESSION_NOT_FOUND,
    ResumeResult.Reason.SESSION_NOT_ACTIVE,
    -> LearningNotice.SessionUnavailable
}

/** 学习屏 UI 状态（PlaybackState 的 app 层投影；Paused(error) 单列为 Error，AUDIO §9）。 */
sealed interface LearningUiState {
    data object Loading : LearningUiState

    data class Playing(
        val wordText: String,
        val groupIndex: Int,
        val segmentLabel: String,
        val segmentIndex: Int,
        val offsetMs: Long,
        val degraded: Boolean,
    ) : LearningUiState

    data class Paused(
        val wordText: String,
        val groupIndex: Int,
        val segmentIndex: Int,
    ) : LearningUiState

    data class CommandWindow(
        val wordText: String,
        val groupIndex: Int,
        val remainingMs: Long,
        val totalMs: Long,
        /** 语音监听中（Phase 5 Step 1）：true = 识别器开窗（请说「会了」）；false = 降级纯倒计时（按钮仍可用）。 */
        val listening: Boolean,
    ) : LearningUiState

    data object Completed : LearningUiState

    data object Stopped : LearningUiState

    /** TTS 失败进入的错误暂停（AUDIO §9 P4 可表达子集：重试 = resume，退出 = exit）。 */
    data class Error(val wordText: String, val groupIndex: Int, val segmentIndex: Int) : LearningUiState
}

private fun PlaybackState.isTransportActive(): Boolean =
    this is PlaybackState.Playing || this is PlaybackState.Paused || this is PlaybackState.CommandWindow

private fun PlaybackState.toUiState(cachedWordText: String?): LearningUiState = when (this) {
    PlaybackState.Idle -> LearningUiState.Loading
    is PlaybackState.Playing -> LearningUiState.Playing(
        wordText = wordText,
        groupIndex = wordRef.groupIndex + 1, // 用户可见组号 1 起（LE spec §4）
        segmentLabel = segment?.type?.label() ?: "",
        segmentIndex = segmentIndex + 1,
        offsetMs = offsetMs,
        degraded = degraded,
    )
    is PlaybackState.Paused ->
        if (error != null) {
            LearningUiState.Error(wordText, wordRef.groupIndex + 1, segmentIndex + 1)
        } else {
            LearningUiState.Paused(wordText, wordRef.groupIndex + 1, segmentIndex + 1)
        }
    is PlaybackState.CommandWindow -> LearningUiState.CommandWindow(
        // CommandWindow 态不携带词文本（PlaybackState 契约）——由调用方传入展示缓存保持 UI 连续性，
        // 非第二播放状态源；窗口归属词恒等于此前 Playing 的词（advance 发生在窗口结束后）。
        wordText = cachedWordText?.takeIf { it.isNotEmpty() } ?: "…",
        groupIndex = wordRef.groupIndex + 1,
        remainingMs = remainingMs,
        totalMs = totalMs,
        listening = listening, // Phase 5 Step 1：监听/降级形态透传（编排器为唯一事实源）
    )
    PlaybackState.Completed -> LearningUiState.Completed
    PlaybackState.Stopped -> LearningUiState.Stopped
}

/** 段类型 → 用户可见文案（app 层展示映射，shared 不携带 UI 文案）。 */
internal fun SegmentType.label(): String = when (this) {
    SegmentType.PRONUNCIATION -> "单词发音"
    SegmentType.SPELLING -> "单词拼写"
    SegmentType.MEANING_EN -> "英文释义"
    SegmentType.MEANING_CN -> "中文释义"
    SegmentType.EXAMPLE_AUDIO -> "例句"
    SegmentType.EXAMPLE_CN -> "例句译文"
}
