package com.vocabularybooster.playback

import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.LearningEngine
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordRef
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.VoiceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.min

/**
 * PlaybackOrchestrator（AUDIO_ENGINE_SPEC §3 状态机，Phase 4 Step 2 + Phase 5 Step 1 语音接入）：
 * 驱动「Segment 播放 → CommandWindow（监听/倒计时）→ 命令或超时 → LearningEngine → 下一词」的播放编排，
 * 对外唯一状态源 [state]；学习侧语义（掌握/推进/完成/退出三分支）全部委托 [LearningEngine]。
 *
 * - **语音接入（Phase 5 Step 1 裁决 D2/D5）**：CommandWindow 在识别器可用时进入监听模式——
 *   `recognizer.listenOnce(剩余窗口)` 循环 + 并行倒计时广播；Hit → [CommandParser]：
 *   MASTERED → [executeMasteredCommand]（markMastered(VOICE) → advance，唯一“会了”路径）；
 *   UNKNOWN → §9「忽略并保持监听直到窗口超时」（不误杀）；Timeout → 窗口耗尽 → advance；
 *   Unavailable（含 [SpeechCommandRecognizer.isAvailable] 为 false 的前置分支）→ 整窗降级纯倒计时，
 *   「会了」按钮照常可用。**识别错误绝不导致 advance/掌握语义之外的行为**。
 * - **L1（P4 边界，历史保留）**：识别器不可用时 CommandWindow 即 P4 纯倒计时形态（时长 =
 *   [LearningSettingsRepository.getCommandWindowMs]，默认 4000ms）。
 * - **L2**：当前词分段为空 → 保持 SessionWord 现状，直通 CommandWindow → advance()，绝不因
 *   空段 MASTERED（窗口内用户显式说「会了」= 正常命令语义，非空段自动掌握）。
 * - **L3**：SessionWord.PLAYING 是词级唯一真相源；playback.position 仅在
 *   `sessionId + wordId 双匹配`时提供 segmentIndex/offsetMs 段级恢复，且永远不反写 SessionWord。
 * - **L4**：每段起播前重读开关（[SegmentBuilder] 游标懒评估），当前段不受开关变化影响。
 * - **重复命令防护（§11）**：[windowCommandConsumed]——同一窗口内语音与按钮至多消费一次；
 *   listenOnce 单次返回 + 单并发会话（actual 契约）保证识别回调不重复执行掌握。
 *
 * 并发模型：公开控制经 [controlMutex] 串行；同一时刻至多一个播放驱动 Job（[stepJob]），
 * 控制类操作先 cancelAndJoin 再改状态——不存在两个 PLAYING 驱动流。advance+换词装载包在
 * [NonCancellable] 中：暂停/取消不会观察到「库已换 PLAYING 词而内存仍是旧词」的半迁移态。
 */
@Suppress("TooManyFunctions", "LongParameterList") // §3 逐事件一个入口 + §6 五控制 + §5 依赖 = 规格装配全量
public class PlaybackOrchestrator(
    private val engine: LearningEngine,
    private val contentRepository: PlaybackContentRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val settingsRepository: LearningSettingsRepository,
    private val audioPlayer: AudioPlayer,
    private val synthesizer: SpeechSynthesizer,
    private val recognizer: SpeechCommandRecognizer,
    private val commandParser: CommandParser,
    private val eventBus: DomainEventBus,
    private val scope: CoroutineScope,
) {

    private val controlMutex = Mutex()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    /** 对外唯一播放状态源（§3：含当前段、倒计时、降级标志）。 */
    public val state: StateFlow<PlaybackState> = _state

    /** 当前播放驱动 Job（段循环/窗口监听/窗口倒计时）；控制操作 cancelAndJoin 后重建。 */
    private var stepJob: Job? = null

    /**
     * §11 重复命令防护：当前 CommandWindow 的「会了」是否已被消费（语音或按钮，先到先得）——
     * 同一窗口至多执行一次掌握。每个窗口开启时重置（[runCommandWindowPhase] 入口）。
     */
    private var windowCommandConsumed: Boolean = false

    // —— 播放游标（仅驱动协程与持锁控制路径读写）——
    private var sessionId: Long? = null
    private var sessionWordBookId: Long = 0L
    private var wordRef: WordRef? = null
    private var wordText: String = ""
    private var specs: List<SegmentSpec> = emptyList()

    /** 下一个待评估 spec 下标；段未完成时即当前段下标（暂停恢复 = 重读本段，§3）。 */
    private var specCursor: Int = 0

    /** 已完成段数 = 状态/位置持久化使用的 segmentIndex。 */
    private var playedCount: Int = 0

    /** 文件段恢复 offset（L3 恢复 / Pause 回传），playAt 消费后清零。 */
    private var resumeOffsetMs: Long = 0L

    /** 播放中的 spec（Pause 判定停哪个端口；段完成置 null → guard 间隙）。 */
    private var currentSpec: SegmentSpec? = null

    // —— 会话接入（§3 Idle 行：Play；§6 Play「已有 ACTIVE 会话按 LEARNING §9 恢复」）——

    /**
     * 开始会话：委托 [LearningEngine.startSession]；Rejected 原样返回（编排器保持 Idle）；
     * Started → 从快照起播（无 PLAYING 词时由引擎 advance() 裁决起始词，L3 步骤 5）。
     */
    public suspend fun startSession(wordBookId: Long): StartResult = controlMutex.withLock {
        val result = engine.startSession(wordBookId)
        if (result is StartResult.Started) {
            adoptSession(result.snapshot)
            beginFromSnapshot(result.snapshot, restorePosition = false)
        }
        result
    }

    /**
     * 恢复会话：委托 [LearningEngine.resumeSession]（含 TC-LE-10 完整性检查）；
     * BOOK_DELETED / 非 ACTIVE 拒绝原样返回（保持 Idle）。
     * 恢复走 L3 双源：先取快照 PLAYING 词，playback.position 仅在 sessionId+wordId
     * 双匹配时提供段级恢复；phase=WINDOW 匹配 → 直接重开该词整窗。
     */
    public suspend fun resumeSession(sessionId: Long): ResumeResult = controlMutex.withLock {
        val result = engine.resumeSession(sessionId)
        if (result is ResumeResult.Resumed) {
            adoptSession(result.snapshot)
            beginFromSnapshot(result.snapshot, restorePosition = true)
        }
        result
    }

    // —— 传输控制（§6，全部幂等；Toggle 类设置不经此——下一 Segment 自动生效，L4）——

    /** Pause：Playing → 记录段位（文件段取 audioPlayer.pause() 返回 offset）；CommandWindow → 窗口位。 */
    public suspend fun pause(): Unit = controlMutex.withLock {
        when (_state.value) {
            is PlaybackState.Playing -> {
                cancelStep()
                val offset = stopCurrentSegmentPorts()
                savePosition(positionAt(playedCount, offset, PlaybackPhase.PLAYING))
                _state.value = pausedState(offset, atCommandWindow = false, error = null)
            }
            is PlaybackState.CommandWindow -> {
                cancelStep() // §3「关识别」：取消窗口 Job（listenOnce 取消 → actual destroy + 停倒计时）；恢复重开整窗
                _state.value = pausedState(offsetMs = 0L, atCommandWindow = true, error = null)
            }
            else -> Unit // Idle/Paused/终态：幂等 no-op
        }
    }

    /** Resume：词中 → 原段重播（TTS 重读本段 / 文件 playAt(offset)）；窗口位 → 重开整窗。绝不整词重播。 */
    public suspend fun resume(): Unit = controlMutex.withLock {
        val current = _state.value
        if (current !is PlaybackState.Paused) return@withLock // 幂等
        if (current.atCommandWindow) {
            stepJob = scope.launch {
                // §3：Paused(窗口位) resume → CommandWindow(重开整窗)——窗口重开（含监听与消费位重置）
                if (!windowAndAdvance(skipGuard = true)) driveLoop()
            }
        } else {
            launchDriveLoop() // specCursor 停在当前段 → 重读本段
        }
    }

    /** Next（FR-11）：纯跳转——cancelAndJoin → 停端口 → 引擎 advance()，不标记掌握；窗口开着则跳过该词。 */
    public suspend fun next(): Unit = controlMutex.withLock {
        if (!_state.value.isTransportActive()) return@withLock
        cancelStep()
        stopPorts()
        if (!advanceAndAdopt()) launchDriveLoop()
    }

    /** Replay：当前词从 seg0 重播；命令窗口若开着则作废（§6）。 */
    public suspend fun replay(): Unit = controlMutex.withLock {
        if (!_state.value.isTransportActive()) return@withLock
        cancelStep()
        stopPorts()
        specCursor = 0
        playedCount = 0
        resumeOffsetMs = 0L
        currentSpec = null
        launchDriveLoop()
    }

    /**
     * 「会了」按钮（FR-7 / NFR-8 手动等价入口，Phase 5 Step 1 裁决 D2）：**仅 CommandWindow 态可用**，
     * 其他状态幂等 no-op。与语音命令汇合于同一执行路径 [executeMasteredCommand]（source=BUTTON，
     * 语义与 VOICE 完全等价——LE spec §6 入口唯一）。[windowCommandConsumed] 保证同一窗口
     * 语音与按钮至多消费一次（§11 重复防护）；执行前 cancelStep 即「关识别」（listenOnce 取消
     * → actual destroy）。
     */
    public suspend fun masterCurrentWord(): Unit = controlMutex.withLock {
        if (_state.value !is PlaybackState.CommandWindow) return@withLock // D2：仅窗口态
        if (windowCommandConsumed) return@withLock // 同窗已有命令消费（语音先到）
        windowCommandConsumed = true
        cancelStep() // 关识别 + 停倒计时
        if (!executeMasteredCommand(MasterySource.BUTTON)) launchDriveLoop()
    }

    /**
     * Exit（§3 exit 行 / §8 退出三分支）：cancelAndJoin → 停端口 → 引擎 exitSession →
     * 清 playback.position → Stopped。终态重入幂等；Idle 无会话可退 = 安全 no-op 返回 null。
     * 分支 C（sessionCompleted=true）→ 发布 WordBookCompleted（Phase 6；NonCancellable：
     * 会话已终态化，COMPLETED 会话不可 resume——发布被取消截断则勋章无自愈重发路径）。
     */
    public suspend fun exit(): ExitResult = controlMutex.withLock {
        when (_state.value) {
            PlaybackState.Completed, PlaybackState.Stopped ->
                return@withLock ExitResult(derivedWordBookId = null)
            else -> Unit
        }
        val sid = sessionId ?: return@withLock ExitResult(derivedWordBookId = null)
        cancelStep()
        stopPorts()
        val result = engine.exitSession(sid)
        positionRepository.clear()
        _state.value = PlaybackState.Stopped
        if (result.sessionCompleted) {
            withContext(NonCancellable) { publishBookCompleted(sid) }
        }
        result
    }

    /** 释放（宿主 onCleared）：停驱动 Job 与播放端口；不动会话状态（学习历史真相在库）。 */
    public fun dispose() {
        stepJob?.cancel()
        synthesizer.stop()
        audioPlayer.stop()
    }

    // —— 驱动循环（单一 Job；CancellationException 顺着协程取消自然退出）——

    private fun launchDriveLoop() {
        stepJob = scope.launch { driveLoop() }
    }

    /** 「播完当前词全部段 → 命令窗口 → 命令/超时推进 → 换词」直到终态或被控制操作取消。 */
    private suspend fun driveLoop() {
        while (true) {
            if (!playWordSegments()) return // TTS 失败已入 Paused(error)
            if (windowAndAdvance(skipGuard = specs.isEmpty())) return // BookComplete → Completed
        }
    }

    /**
     * 窗口 + 推进一步（§3 CommandWindow 两行）：命中命令 → [executeMasteredCommand]（VOICE）；
     * 超时/降级 → [advanceAndAdopt]（既有超时语义）。返回 true = BookComplete 终态。
     */
    private suspend fun windowAndAdvance(skipGuard: Boolean): Boolean {
        val mastered = runCommandWindowPhase(skipGuard) // L2：空词直通窗口（skipGuard）
        return if (mastered) {
            executeMasteredCommand(MasterySource.VOICE)
        } else {
            advanceAndAdopt()
        }
    }

    /** 游标逐段起播（L4：每段重读开关）；返回 false = 段播放失败（已置 Paused(error)）。 */
    private suspend fun playWordSegments(): Boolean {
        while (true) {
            val specIndex = nextEnabledSpecIndex() ?: return true // 词内段尽（或空词，L2）
            if (!playSegmentAt(specIndex)) return false
        }
    }

    private suspend fun nextEnabledSpecIndex(): Int? {
        val toggles = settingsRepository.getPlaybackToggles() // L4：每段重读有效开关
        var index = specCursor
        while (index < specs.size) {
            if (toggles.enables(specs[index].type)) return index
            index++
        }
        return null
    }

    /** 起播一段：写位置（NFR-3）→ 置 Playing → 执行（§9 降级即时广播）→ 完成推进游标；false = 失败已入 Paused(error)。 */
    private suspend fun playSegmentAt(specIndex: Int): Boolean {
        val spec = specs[specIndex]
        val offset = if (spec.isFileSegment()) resumeOffsetMs else 0L
        resumeOffsetMs = 0L
        currentSpec = spec
        savePosition(positionAt(playedCount, offset, PlaybackPhase.PLAYING))
        _state.value = playingState(spec, offset, degraded = false)

        val handledByFile = spec.isFileSegment() && fileSegmentPlayed(spec, offset)
        if (!handledByFile) {
            if (spec.isFileSegment()) {
                _state.value = playingState(spec, offset, degraded = true) // 兜底即广播降级标志（§3）
            }
            if (portCall { synthesizer.speak(speakRequestFor(spec)) } == null) {
                // §9 P4 可表达子集：TTS 失败 → Paused(error)，会话/掌握状态不动，位置已存于段起播
                _state.value = pausedState(offset, atCommandWindow = false, error = PlaybackError.TTS_FAILED)
                return false
            }
        }
        completeSegment(specIndex)
        return true
    }

    /** 文件段执行（加载 + 定点播放）；false = 平台失败，调用方转 TTS 兜底（§9 / TC-AE-12）。 */
    private suspend fun fileSegmentPlayed(spec: SegmentSpec, offsetMs: Long): Boolean =
        portCall {
            audioPlayer.prepare(spec.track!!)
            audioPlayer.playAt(offsetMs)
        } != null

    /**
     * §9 端口边界：平台侧任何异常统一转为 null 失败信号（加载/播放/TTS 失败一律走兜底或 Paused），
     * 调用方按 §9 矩阵消发；协程取消不是端口失败，照常传播。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> portCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun completeSegment(specIndex: Int) {
        specCursor = specIndex + 1
        playedCount++
        currentSpec = null
    }

    /** 组装 TTS 请求（§1/§3 裁决 L6）：语言逐段切换；rate = 段基础倍率 × ttsRate 设置；pitch 取设置。 */
    private suspend fun speakRequestFor(spec: SegmentSpec): SpeakRequest = SpeakRequest(
        utteranceId = "seg-${activeSessionId()}-${activeWordId()}-$playedCount",
        text = spec.text.orEmpty(),
        lang = spec.lang,
        rate = spec.rateScale * settingsRepository.getTtsRate(),
        pitch = settingsRepository.getTtsPitch(),
    )

    /**
     * CommandWindow（§3/§4，Phase 5 Step 1 裁决 D2/D5）：guard 300ms（§3 常量）→ 写 WINDOW 位置 →
     * 窗口开（重置 [windowCommandConsumed]）→ **监听模式**（识别器可用）或**纯倒计时**（不可用，§9 降级）。
     *
     * 监听模式：倒计时 ticker（状态广播）与 `listenOnce(剩余窗口)` 循环并行——
     * - Hit(text) → [CommandParser]：MASTERED → 关窗（取消 ticker）返回 true（调用方执行掌握+推进）；
     *   UNKNOWN → **忽略并保持监听直到窗口超时**（§9 不误杀），`yield()` 保证取消点；
     * - Timeout → 本次尝试已耗尽剩余窗口 → 窗口自然结束（ticker 跑满）；
     * - Unavailable → 可用性级错误 → [degraded] 整窗降级纯倒计时（按钮仍可用），不掌握不提前 advance。
     *
     * [skipGuard] = L2 空词直通 / 窗口恢复重开（无在播 TTS，无守卫必要）。
     * 返回 true = 命令已命中（调用方 [executeMasteredCommand]）；false = 窗口超时（调用方 advance）。
     */
    private suspend fun runCommandWindowPhase(skipGuard: Boolean): Boolean {
        if (!skipGuard) delay(GUARD_DELAY_MS)
        val totalMs = settingsRepository.getCommandWindowMs()
        savePosition(positionAt(playedCount, 0L, PlaybackPhase.WINDOW))
        val ref = wordRef ?: return false
        windowCommandConsumed = false
        val listeningEnabled = recognizer.isAvailable.value
        _state.value = PlaybackState.CommandWindow(ref, totalMs, totalMs, listeningEnabled)
        var elapsed = 0L
        var degraded = false
        var mastered = false
        coroutineScope {
            // 倒计时 ticker：窗口时间轴唯一驱动（监听/降级两种模式共用）；每 tick 广播状态
            val ticker = launch {
                while (elapsed < totalMs) {
                    delay(WINDOW_TICK_MS)
                    elapsed = min(elapsed + WINDOW_TICK_MS, totalMs)
                    _state.value = PlaybackState.CommandWindow(
                        ref,
                        totalMs - elapsed,
                        totalMs,
                        listening = listeningEnabled && !degraded,
                    )
                }
            }
            if (listeningEnabled) {
                when (listenForMasteredCommand(totalMs) { elapsed }) {
                    WindowListenOutcome.MASTERED -> mastered = true
                    WindowListenOutcome.TIMED_OUT -> Unit // ticker 收尾（窗口自然结束）
                    WindowListenOutcome.DEGRADED -> degraded = true // §9：整窗降级（ticker 已广播 listening=false）
                }
            }
            if (mastered) ticker.cancel() // 命令即关窗：不等倒计时跑满
            // 超时/降级：等 ticker 自然跑满窗口（保持既有「窗口结束 → advance」语义）
        }
        if (mastered) windowCommandConsumed = true // 命令消费点：先到先得，按钮此后不再受理
        return mastered
    }

    /**
     * 窗口监听循环（§3/§9，裁决 D5）：`listenOnce(剩余窗口)` 逐次尝试至终局——
     * MASTERED（Hit 命中「会了」）/ TIMED_OUT（预算静默耗尽或识别超时）/ DEGRADED（可用性级错误）。
     * UNKNOWN 噪音 → 保持监听直到窗口超时（§9 不误杀）；**任何错误都不产生命令语义**。
     */
    private suspend fun listenForMasteredCommand(
        totalMs: Long,
        elapsed: () -> Long,
    ): WindowListenOutcome {
        var outcome: WindowListenOutcome? = null // null = 尚无终局（UNKNOWN 后继续监听）
        while (outcome == null && elapsed() < totalMs) {
            when (val result = recognizer.listenOnce(totalMs - elapsed())) {
                is RecognitionResult.Hit -> {
                    val command = commandParser.parse(
                        result.text,
                        CommandParser.DEFAULT_MASTERED_ALIASES,
                        CommandParser.V1_ENABLED,
                    )
                    if (command == VoiceCommand.MASTERED) {
                        outcome = WindowListenOutcome.MASTERED
                    } else {
                        yield() // UNKNOWN → 保持监听；让出调度（取消点，真实 actual 必然挂起）
                    }
                }
                RecognitionResult.Timeout -> outcome = WindowListenOutcome.TIMED_OUT
                RecognitionResult.Unavailable -> outcome = WindowListenOutcome.DEGRADED
            }
        }
        return outcome ?: WindowListenOutcome.TIMED_OUT // 窗口预算自然耗尽
    }

    /** [listenForMasteredCommand] 终局（MASTERED = 命令已命中，由调用方执行掌握+推进）。 */
    private enum class WindowListenOutcome { MASTERED, TIMED_OUT, DEGRADED }

    // —— 会话/词装载 ——

    /**
     * 当前（或最近一次接入的）会话所属生词本；未接入过会话 → null。
     * 只读定位信息（Phase 6）：完成仪式页按书查 BOOK_COMPLETED 勋章用，不参与播放裁决。
     */
    public fun activeWordBookId(): Long? = sessionId?.let { sessionWordBookId }

    private fun adoptSession(snapshot: SessionSnapshot) {
        sessionId = snapshot.session.sessionId
        sessionWordBookId = snapshot.session.wordBookId
    }

    /**
     * 快照 → 起播（L3 五步）：取 PLAYING 词（无 → 引擎 advance 裁决起始词，position 不创造播放位）；
     * restorePosition=true 时读 playback.position 做段级恢复（双匹配才生效）。
     */
    private suspend fun beginFromSnapshot(snapshot: SessionSnapshot, restorePosition: Boolean) {
        cancelStep()
        val playing = snapshot.words.firstOrNull { it.status == SessionWordStatus.PLAYING }
        val startRef = playing?.toWordRef()
            ?: when (val advanced = engine.advance(snapshot.session.sessionId)) {
                is AdvanceResult.NextWord -> advanced.ref
                is AdvanceResult.BookComplete -> null
            }
        if (startRef == null) {
            finishAsCompleted()
            return
        }
        loadWord(startRef)
        if (restorePosition) {
            val position = positionRepository.get()
            if (position != null && position.sessionId == snapshot.session.sessionId &&
                position.wordId == startRef.wordId
            ) {
                if (position.phase == PlaybackPhase.WINDOW) {
                    // 词已完成（存档相位 = 窗口）→ 直接重开整窗；对齐 §3「窗口恢复 = 重开整窗」
                    stepJob = scope.launch {
                        if (!windowAndAdvance(skipGuard = true)) driveLoop()
                    }
                    return
                }
                applyPlayingRestore(position)
            }
        }
        launchDriveLoop()
    }

    /** L3 步骤 3：段级恢复——对齐到当前开关下的第 N 个已启用段；越界 = 恢复信息不可用 → 从 seg0（类比步骤 4）。 */
    private suspend fun applyPlayingRestore(position: PlaybackPosition) {
        val toggles = settingsRepository.getPlaybackToggles()
        val enabledSpecs = specs.withIndex().filter { toggles.enables(it.value.type) }
        val target = position.segmentIndex
        if (target in enabledSpecs.indices) {
            specCursor = enabledSpecs[target].index
            playedCount = target
            resumeOffsetMs = position.offsetMs
        }
    }

    /** 装载一个词的分段（Q4/Q4b 内容 → §2 规格）；null 内容（词条已移除等）按空词处理（L2 直通窗口）。 */
    private suspend fun loadWord(ref: WordRef) {
        wordRef = ref
        val content = contentRepository.getPlaybackContent(sessionWordBookId, ref.wordId)
        wordText = content?.word?.text.orEmpty()
        specs = content?.let { SegmentBuilder.buildSpecs(it) }.orEmpty()
        specCursor = 0
        playedCount = 0
        resumeOffsetMs = 0L
        currentSpec = null
    }

    /**
     * 「会了」唯一执行路径（FR-7 / Phase 5 裁决 D2：语音与按钮在此汇合，无第二套掌握语义）：
     * [LearningEngine.markMastered]（source=VOICE|BUTTON，引擎入口唯一、幂等）→ 既有 [advanceAndAdopt]
     * （推进/完成裁决仍归引擎）。包 [NonCancellable]：掌握落库与换词装载原子完成。
     * 终态裁决：BookComplete → Completed（advance 内含）；否则 false（调用方继续驱动）。
     */
    private suspend fun executeMasteredCommand(source: MasterySource): Boolean = withContext(NonCancellable) {
        engine.markMastered(activeSessionId(), activeWordId(), source)
        advanceAndAdopt()
    }

    /**
     * 窗口后推进（超时/降级路径：倒计时结束 → advance）→ 换词或终态。
     * 包 [NonCancellable]：advance（库内 PLAYING 迁移）与游标装载原子完成，
     * 暂停取消不产生「库已换词、内存未跟上」的半迁移态。
     */
    private suspend fun advanceAndAdopt(): Boolean = withContext(NonCancellable) {
        when (val result = engine.advance(activeSessionId())) {
            is AdvanceResult.NextWord -> {
                loadWord(result.ref)
                false
            }
            is AdvanceResult.BookComplete -> {
                finishAsCompleted()
                true
            }
        }
    }

    /** 终态完成（TC-AE-14）：先停全部播放端口 → 清 playback.position（§5）→ 暴露 Completed →
     *  发布完成事件（Phase 6，TC-AC-05：事件必须在端口停止之后）。 */
    private suspend fun finishAsCompleted() {
        stopPorts()
        positionRepository.clear()
        _state.value = PlaybackState.Completed
        sessionId?.let { publishBookCompleted(it) }
    }

    /**
     * 发布 WordBookCompleted（Phase 6，FR-8/TC-AC-05 顺序锚点）：
     * 仅在 stopPorts 之后调用（[finishAsCompleted] / [exit] 分支 C 两锚点）。
     * 重复发布（终态会话 resume → finishAsCompleted 重入）由消费侧防御复核 +
     * 幂等授予兜住（ACHIEVEMENT_SPEC §2 三层幂等）。
     */
    private suspend fun publishBookCompleted(sid: Long) {
        eventBus.publish(
            DomainEvent.WordBookCompleted(sessionId = sid, wordBookId = sessionWordBookId),
        )
    }

    // —— 端口与状态小工具 ——

    private suspend fun stopCurrentSegmentPorts(): Long {
        val spec = currentSpec
        return if (spec != null && spec.isFileSegment()) {
            audioPlayer.pause().also { resumeOffsetMs = it } // §3：文件段 pause() 记 offsetMs
        } else {
            synthesizer.stop() // §3：TTS 段 stop()，恢复重读本段
            0L
        }
    }

    private fun stopPorts() {
        synthesizer.stop()
        audioPlayer.stop()
    }

    private suspend fun cancelStep() {
        stepJob?.cancelAndJoin()
        stepJob = null
    }

    private suspend fun savePosition(position: PlaybackPosition) {
        positionRepository.save(position)
    }

    private fun positionAt(index: Int, offsetMs: Long, phase: PlaybackPhase): PlaybackPosition =
        PlaybackPosition(activeSessionId(), activeWordId(), index, offsetMs, phase)

    private fun playingState(spec: SegmentSpec, offsetMs: Long, degraded: Boolean): PlaybackState.Playing =
        PlaybackState.Playing(
            wordRef = wordRef ?: error("播放中必须有词"),
            wordText = wordText,
            segmentIndex = playedCount,
            segment = spec.toSegment(playedCount),
            offsetMs = offsetMs,
            degraded = degraded,
        )

    private fun pausedState(offsetMs: Long, atCommandWindow: Boolean, error: PlaybackError?): PlaybackState.Paused =
        PlaybackState.Paused(
            wordRef = wordRef ?: error("暂停中必须有词"),
            wordText = wordText,
            segmentIndex = playedCount,
            offsetMs = offsetMs,
            atCommandWindow = atCommandWindow,
            error = error,
        )

    private fun activeSessionId(): Long = sessionId ?: error("无活动会话")

    private fun activeWordId(): Long = wordRef?.wordId ?: error("无活动词")

    private fun SessionWord.toWordRef(): WordRef = WordRef(sessionId, wordId, groupIndex, orderInGroup)

    private companion object {
        /** §3 裁决 L6：guardDelay = 300ms 为 v1 常量（FR-12 默认值，未设设置键）。 */
        const val GUARD_DELAY_MS: Long = 300L

        /** 窗口剩余时长状态刷新粒度（仅影响状态可见性，不影响窗口语义）。 */
        const val WINDOW_TICK_MS: Long = 100L
    }
}

/** §1 播放方式：EXAMPLE_AUDIO 且有 track → 文件音频（AudioPlayer）；否则 TTS。 */
private fun SegmentSpec.isFileSegment(): Boolean = type == SegmentType.EXAMPLE_AUDIO && track != null

/** §6 控制作用域：传输控制只在 Playing/Paused/CommandWindow 有意义（终态/Idle 幂等 no-op）。 */
private fun PlaybackState.isTransportActive(): Boolean =
    this is PlaybackState.Playing || this is PlaybackState.Paused || this is PlaybackState.CommandWindow
