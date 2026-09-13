package com.vocabularybooster.playback

import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放端口手写 Fake（CLAUDE.md 测试规范；AUDIO_ENGINE_SPEC §10 可测性）：
 * 段完成 / 加载失败 / TTS 失败 / 暂停 offset 全部可编程，时间经虚拟 delay 推进。
 */

/** Fake TTS：记录全部 speak 请求；每段 [segmentDurationMs] 虚拟时长后完成；[failTexts] 命中即抛。 */
internal class FakeSpeechSynthesizer(
    private val segmentDurationMs: Long = 100L,
) : SpeechSynthesizer {

    val requests = mutableListOf<SpeakRequest>()
    val failTexts = mutableSetOf<String>()
    var stopCount = 0
        private set
    var concurrent = 0
        private set
    var maxConcurrent = 0
        private set

    override val readiness: StateFlow<Readiness> = MutableStateFlow(Readiness.READY)

    override suspend fun speak(request: SpeakRequest): SegmentResult {
        requests += request
        if (request.text in failTexts) throw IllegalStateException("TTS 失败（注入）：${request.text}")
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            delay(segmentDurationMs)
        } finally {
            concurrent--
        }
        return SegmentResult(utteranceId = request.utteranceId, completed = true, durationMs = segmentDurationMs)
    }

    override fun stop() {
        stopCount++
    }
}

/** Fake 文件音频：[failPrepare]/[failPlay] 注入 §9 降级路径；[pauseOffsetMs] 为 pause() 回传值。 */
internal class FakeAudioPlayer : AudioPlayer {

    val preparedTracks = mutableListOf<TrackDescriptor>()
    val playAtOffsets = mutableListOf<Long>()
    var failPrepare = false
    var failPlay = false
    var pauseOffsetMs = 0L
    var pauseCount = 0
        private set
    var stopCount = 0
        private set

    override val progressMs: StateFlow<Long?> = MutableStateFlow(null)

    override suspend fun prepare(track: TrackDescriptor) {
        preparedTracks += track
        if (failPrepare) throw IllegalStateException("音频加载失败（注入）：${track.audioUri}")
    }

    override suspend fun playAt(offsetMs: Long): SegmentResult {
        playAtOffsets += offsetMs
        if (failPlay) throw IllegalStateException("音频播放失败（注入）")
        delay(100L)
        return SegmentResult(utteranceId = "audio", completed = true)
    }

    override fun pause(): Long {
        pauseCount++
        return pauseOffsetMs
    }

    override fun stop() {
        stopCount++
    }
}

/** Fake 播放内容：(wordBookId, wordId) → 内容；未登记 = 词条缺失（L2 空词路径数据源）。 */
internal class FakePlaybackContentRepository : PlaybackContentRepository {

    val contents = mutableMapOf<Pair<Long, Long>, PlaybackContent>()

    override suspend fun getPlaybackContent(wordBookId: Long, wordId: Long): PlaybackContent? =
        contents[wordBookId to wordId]
}

/** Fake 位置仓储：记录 save 序列与 clear 次数；[current] 可预置（L3 恢复注入）。 */
internal class FakePlaybackPositionRepository : PlaybackPositionRepository {

    var current: PlaybackPosition? = null
    val saves = mutableListOf<PlaybackPosition>()
    var clearCount = 0
        private set

    override suspend fun save(position: PlaybackPosition) {
        current = position
        saves += position
    }

    override suspend fun get(): PlaybackPosition? = current

    override suspend fun clear() {
        clearCount++
        current = null
    }
}

/** Fake 设置源（L4：段间直接改 [toggles]/[commandWindowMs] 等字段即可生效）。 */
internal class FakeLearningSettingsRepository(
    var groupSize: Int = LearningSettingsRepository.DEFAULT_GROUP_SIZE,
    var toggles: PlaybackToggles = PlaybackToggles.DEFAULT,
    var commandWindowMs: Long = LearningSettingsRepository.DEFAULT_COMMAND_WINDOW_MS,
    var ttsRate: Float = LearningSettingsRepository.DEFAULT_TTS_RATE,
    var ttsPitch: Float = LearningSettingsRepository.DEFAULT_TTS_PITCH,
) : LearningSettingsRepository {
    override suspend fun getGroupSize(): Int = groupSize
    override suspend fun getPlaybackToggles(): PlaybackToggles = toggles
    override suspend fun getCommandWindowMs(): Long = commandWindowMs
    override suspend fun getTtsRate(): Float = ttsRate
    override suspend fun getTtsPitch(): Float = ttsPitch
}

/**
 * Fake 识别器（§10 可测性；TC-AE-04/05/06/25）：
 * - [script] 依次出队**立即**返回（识别瞬间完成，不耗虚拟时间——编排器倒计时 ticker 照常走表）；
 * - 队列耗尽 = 静默真实语义：`delay(windowMs)` 后 Timeout（窗口时间照常流逝，超时语义可虚拟时间断言）；
 * - [available] = isAvailable 初值。**默认 false = P4 降级形态**（既有 Step 2 测试全部保持纯倒计时，
 *   零识别行为）；语音测试置 true 并按需装 [script]。
 * - 观测：listenCalls（每次窗口预算入参）/ cancelledCount（取消 = 编排器「关识别」证据）。
 */
internal class FakeSpeechCommandRecognizer(
    available: Boolean = false,
) : SpeechCommandRecognizer {

    override val isAvailable: MutableStateFlow<Boolean> = MutableStateFlow(available)

    val script = ArrayDeque<RecognitionResult>()
    val listenCalls = mutableListOf<Long>()
    var cancelledCount = 0
        private set
    var inFlight = 0
        private set

    override suspend fun listenOnce(windowMs: Long): RecognitionResult {
        listenCalls += windowMs
        inFlight++
        try {
            script.removeFirstOrNull()?.let { return it }
            delay(windowMs) // 静默 = 窗口预算耗尽（与 Android actual 的窗口超时语义一致）
            return RecognitionResult.Timeout
        } catch (e: CancellationException) {
            cancelledCount++
            throw e
        } finally {
            inFlight--
        }
    }
}
