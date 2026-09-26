package com.vocabularybooster.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.playback.PlaybackState
import com.vocabularybooster.playback.Segment
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
    private val achievementRepository: AchievementRepository,
    private val eventBus: DomainEventBus,
) : ViewModel() {

    /** 学习屏 UI 状态（PlaybackState 的 app 层 immutable 投影）。 */
    var ui by mutableStateOf<LearningUiState>(LearningUiState.Loading)
        private set

    /**
     * 当前词展示详情（音标 + 释义/例句卡片数据，bug list「学习会话显示完整词信息」）：
     * 换词时异步重载（编排器 getCurrentContent），加载间隙为 null——面板收起，不闪旧词。
     */
    var wordDetail by mutableStateOf<LearningWordDetail?>(null)
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

    /**
     * 展示连续性：最近一次 Playing 携带的段。Paused 不携带段（PlaybackState 契约）——
     * 暂停冻结的就是该段，恢复也重读它（ADR-09），用缓存补齐 UI 高亮。
     */
    private var lastSegment: Segment? = null

    /** wordDetail 归属词（wordId 去重：段推进不重载，仅换词重载）。 */
    private var detailWordId: Long? = null

    /** 完成仪式（Phase 6）：已请求过勋章快照的书（StateFlow 重复发射去重）。 */
    private var ceremonyBookId: Long? = null

    init {
        viewModelScope.launch {
            orchestrator.state.collect { state ->
                if (state.isTransportActive()) sessionOwned = true
                if (!suppressingUiUpdates && sessionOwned) {
                    ui = mapToUiState(state)
                    if (state is PlaybackState.Completed) ensureCeremonyMedal()
                    refreshWordDetail(state)
                }
            }
        }
        // Phase 6：勋章授予事件 → 仪式页刷新（首查可能早于异步授予落库，事件兜住竞态）
        viewModelScope.launch {
            eventBus.events.collect { event ->
                val unlockedBookId = (event as? DomainEvent.AchievementUnlocked)?.wordBookId
                if (unlockedBookId != null &&
                    unlockedBookId == orchestrator.activeWordBookId() &&
                    ui is LearningUiState.Completed
                ) {
                    loadCeremonyMedal(unlockedBookId)
                }
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

    // —— 传输控制（AUDIO §6 六控制（v2.9 +Previous），全部经编排器；不触碰 AudioPlayer/SpeechSynthesizer）——

    fun pause() = launchCommand { orchestrator.pause() }

    fun resume() = launchCommand { orchestrator.resume() }

    fun previous() = launchCommand { orchestrator.previous() }

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
        lastSegment = null
        ceremonyBookId = null
        detailWordId = null
        wordDetail = null
    }

    // —— 完成仪式（Phase 6，ACHIEVEMENT_SPEC §3）：Completed 态按会话所属书解析勋章快照 ——

    /** StateFlow 重复发射去重：每本书只主动查一次；授予竞态（授予晚于首查）由 AchievementUnlocked 事件刷新兜住。 */
    private fun ensureCeremonyMedal() {
        val bookId = orchestrator.activeWordBookId() ?: return
        if (ceremonyBookId == bookId) return
        ceremonyBookId = bookId
        loadCeremonyMedal(bookId)
    }

    private fun loadCeremonyMedal(bookId: Long) {
        viewModelScope.launch {
            runCatching { achievementRepository.getBookCompletedFor(bookId) }
                // 失败静默：仪式页保持通用完成文案（勋章授予失败隔离语义，不阻断呈现）
                .onSuccess { achievement ->
                    if (ui is LearningUiState.Completed) {
                        ui = LearningUiState.Completed(
                            medal = achievement?.let {
                                CompletedMedal(
                                    bookName = it.payload.bookName,
                                    wordCount = it.payload.wordCount,
                                    finishedAtEpochMs = it.earnedAt.toEpochMilliseconds(),
                                )
                            },
                        )
                    }
                }
        }
    }

    /** PlaybackState → UI 投影（词文本/段缓存随携带态刷新，供 Paused/CommandWindow 显示延续）。 */
    private fun mapToUiState(state: PlaybackState): LearningUiState {
        when (state) {
            is PlaybackState.Playing -> {
                wordTextCache = state.wordText
                lastSegment = state.segment
            }
            is PlaybackState.Paused -> wordTextCache = state.wordText
            else -> Unit
        }
        return state.toUiState(wordTextCache, lastSegment)
    }

    /**
     * 词内容卡片装载（纯展示，不参与播放裁决）：wordId 变化才重载；
     * 加载期间又换词 → 结果按 wordId 守卫丢弃，不回写旧词内容。
     */
    private fun refreshWordDetail(state: PlaybackState) {
        val wordId = when (state) {
            is PlaybackState.Playing -> state.wordRef.wordId
            is PlaybackState.Paused -> state.wordRef.wordId
            is PlaybackState.CommandWindow -> state.wordRef.wordId
            else -> null
        }
        if (wordId == null) {
            detailWordId = null
            wordDetail = null
            return
        }
        if (detailWordId == wordId) return
        detailWordId = wordId
        wordDetail = null // 换词先清旧卡（加载间隙面板收起，不闪旧词内容）
        viewModelScope.launch {
            val content = runCatching { orchestrator.getCurrentContent() }.getOrNull()
            if (detailWordId == wordId) {
                wordDetail = content?.toLearningWordDetail()
            }
        }
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

/** 完成仪式快照（Phase 6，ACHIEVEMENT_SPEC §3）：书名/词数/完成时刻（epoch 毫秒，格式化在 Screen）。 */
data class CompletedMedal(
    val bookName: String,
    val wordCount: Int,
    val finishedAtEpochMs: Long,
)

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
        /** 当前段（含 owner 归属 + type 类型，UI 卡片高亮联动）；末段后 300ms 间隙内保持该段。 */
        val currentSegment: Segment?,
    ) : LearningUiState

    data class Paused(
        val wordText: String,
        val groupIndex: Int,
        val segmentIndex: Int,
        /** 冻结段的最近 Playing 快照（Paused 状态不携带段；暂停位 = 该段，ADR-09）。 */
        val currentSegment: Segment?,
    ) : LearningUiState

    data class CommandWindow(
        val wordText: String,
        val groupIndex: Int,
        val remainingMs: Long,
        val totalMs: Long,
        /** 语音监听中（Phase 5 Step 1）：true = 识别器开窗（请说「会了」）；false = 降级纯倒计时（按钮仍可用）。 */
        val listening: Boolean,
    ) : LearningUiState

    /** 完成（编排器权威终态）：medal = BOOK_COMPLETED 快照（Phase 6 仪式页）；null = 未获得/加载中。 */
    data class Completed(val medal: CompletedMedal?) : LearningUiState

    data object Stopped : LearningUiState

    /** TTS 失败进入的错误暂停（AUDIO §9 P4 可表达子集：重试 = resume，退出 = exit）。 */
    data class Error(
        val wordText: String,
        val groupIndex: Int,
        val segmentIndex: Int,
        val currentSegment: Segment?,
    ) : LearningUiState
}

private fun PlaybackState.isTransportActive(): Boolean =
    this is PlaybackState.Playing || this is PlaybackState.Paused || this is PlaybackState.CommandWindow

private fun PlaybackState.toUiState(cachedWordText: String?, cachedSegment: Segment?): LearningUiState =
    when (this) {
        PlaybackState.Idle -> LearningUiState.Loading
        is PlaybackState.Playing -> LearningUiState.Playing(
            wordText = wordText,
            groupIndex = wordRef.groupIndex + 1, // 用户可见组号 1 起（LE spec §4）
            segmentLabel = segment?.type?.label() ?: "",
            segmentIndex = segmentIndex + 1,
            offsetMs = offsetMs,
            degraded = degraded,
            currentSegment = segment,
        )
        is PlaybackState.Paused ->
            if (error != null) {
                LearningUiState.Error(
                    wordText,
                    wordRef.groupIndex + 1,
                    segmentIndex + 1,
                    cachedSegment,
                )
            } else {
                LearningUiState.Paused(wordText, wordRef.groupIndex + 1, segmentIndex + 1, cachedSegment)
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
        PlaybackState.Completed -> LearningUiState.Completed(medal = null) // 勋章快照经 ensureCeremonyMedal 异步补填
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

// —— 学习屏词内容卡片（展示投影，纯渲染数据；播放语义全在编排器/Segment）——

/** 音标（美/英，FR-22 有则双显）+ 释义卡列表（bug list：学习会话显示当前词完整信息）。 */
data class LearningWordDetail(
    val ipaAm: String?,
    val ipaBr: String?,
    val definitions: List<LearningDefinitionCard>,
)

/** 一张释义卡：MeaningEN/MeaningCN 同卡（FR-2 不可拆分），挂本释义选中的例句。 */
data class LearningDefinitionCard(
    val definitionEntryId: Long,
    val partOfSpeech: String,
    val meaningEN: String,
    val meaningCN: String,
    val examples: List<LearningExampleCard>,
)

/** 一条例句卡：句 + 译文（原子单元，FR-3）。 */
data class LearningExampleCard(
    val exampleId: Long,
    val sentence: String,
    val chineseTranslation: String,
)

/** PlaybackContent（选中项读模型）→ 卡片投影：只留渲染字段，例句防御性按 exampleOrder 排。 */
private fun PlaybackContent.toLearningWordDetail(): LearningWordDetail = LearningWordDetail(
    ipaAm = word.ipaAm,
    ipaBr = word.ipaBr,
    definitions = selectedDefinitions.map { definition ->
        LearningDefinitionCard(
            definitionEntryId = definition.definitionEntryId,
            partOfSpeech = definition.partOfSpeech,
            meaningEN = definition.meaningEN,
            meaningCN = definition.meaningCN,
            examples = examplesByDefinitionEntryId[definition.definitionEntryId].orEmpty()
                .sortedBy { it.exampleOrder }
                .map { example ->
                    LearningExampleCard(
                        exampleId = example.exampleId,
                        sentence = example.sentence,
                        chineseTranslation = example.chineseTranslation,
                    )
                },
        )
    },
)
