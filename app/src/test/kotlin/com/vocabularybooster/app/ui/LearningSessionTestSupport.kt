package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.AchievementType
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.GrantAchievementResult
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.LearningEngine
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordRef
import com.vocabularybooster.playback.AudioPlayer
import com.vocabularybooster.playback.PlaybackPosition
import com.vocabularybooster.playback.PlaybackPhase
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

/**
 * Phase 4 Step 4 ViewModel 单测的手写 Fake 端口（TEST_PLAN §1：只用手写 Fake，不用 mock 框架）。
 * 真实 PlaybackOrchestrator + Fake 端口驱动——VM 测试的是映射与转发，不是状态机本身
 * （状态机已由 Step 2 测试锁死）。
 */

/** TTS Fake：speak 由测试逐 utterance 放行（CompletableDeferred 门），记录请求与 stop。 */
class FakeSpeechSynthesizer : SpeechSynthesizer {
    override val readiness = MutableStateFlow(Readiness.READY)
    val requests = mutableListOf<SpeakRequest>()
    val gates = mutableListOf<CompletableDeferred<SegmentResult>>()
    var stopCount = 0
        private set

    /** 为 true 时 speak 抛异常（编排器 §9 → Paused(error=TTS_FAILED) 路径）。 */
    var throwOnSpeak: Boolean = false

    override suspend fun speak(request: SpeakRequest): SegmentResult {
        if (throwOnSpeak) error("tts unavailable (fake)")
        requests += request
        val gate = CompletableDeferred<SegmentResult>()
        gates += gate
        return gate.await()
    }

    override fun stop() {
        stopCount++
        gates.forEach { it.cancel() }
    }

    /** 放行当前挂起的 utterance（完成）。 */
    fun releaseLast(completed: Boolean = true) {
        gates.lastOrNull()?.complete(
            SegmentResult(utteranceId = requests.lastOrNull()?.utteranceId ?: "id", completed = completed),
        )
    }
}

/** 文件音频 Fake：VM 单测走纯 TTS 路径，仅记录端口调用（文件段契约见 Step 2/3 测试）。 */
class FakeAudioPlayer : AudioPlayer {
    override val progressMs = MutableStateFlow<Long?>(null)
    var prepareCount = 0
        private set
    var playCount = 0
        private set
    var pauseCount = 0
        private set
    var stopCount = 0
        private set
    var lastPlayAtOffsetMs = -1L
        private set

    override suspend fun prepare(track: com.vocabularybooster.playback.TrackDescriptor) {
        prepareCount++
    }

    override suspend fun playAt(offsetMs: Long): SegmentResult {
        playCount++
        lastPlayAtOffsetMs = offsetMs
        return SegmentResult(utteranceId = "track", completed = true)
    }

    override fun pause(): Long {
        pauseCount++
        return 0L
    }

    override fun stop() {
        stopCount++
    }
}

/** 引擎 Fake：start/resume 结果与 advance 队列可脚本化，全部调用可观测。 */
class FakeLearningEngine(
    var startResult: StartResult,
    var resumeResult: ResumeResult = ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_FOUND),
) : LearningEngine {
    val startCalls = mutableListOf<Long>()
    val resumeCalls = mutableListOf<Long>()
    val advanceCalls = mutableListOf<Long>()
    val exitCalls = mutableListOf<Long>()
    var bookCompleteOnAdvance = false

    /** advance 词序（循环取用）；耗尽后按 [bookCompleteOnAdvance] 决定终态。 */
    var advanceWordIds: List<Long> = emptyList()

    override suspend fun startSession(wordBookId: Long): StartResult {
        startCalls += wordBookId
        return startResult
    }

    override suspend fun resumeSession(sessionId: Long): ResumeResult {
        resumeCalls += sessionId
        return resumeResult
    }

    /** 掌握调用全记录（Phase 5 Step 1：source=VOICE|BUTTON 可观测——按钮/语音同路径断言依据）。 */
    val markMasteredCalls = mutableListOf<Triple<Long, Long, MasterySource>>()

    override suspend fun markMastered(sessionId: Long, wordId: Long, source: MasterySource): MasteryResult {
        markMasteredCalls += Triple(sessionId, wordId, source)
        return MasteryResult.Marked(Instant.fromEpochMilliseconds(0))
    }

    override suspend fun advance(sessionId: Long): AdvanceResult {
        advanceCalls += sessionId
        val wordId = if (bookCompleteOnAdvance) null else advanceWordIds.getOrNull(advanceCalls.size - 1)
        return if (wordId == null) {
            AdvanceResult.BookComplete
        } else {
            AdvanceResult.NextWord(
                WordRef(sessionId, wordId, groupIndex = 0, orderInGroup = advanceCalls.size - 1),
                null,
            )
        }
    }

    override suspend fun exitSession(sessionId: Long): ExitResult {
        exitCalls += sessionId
        return ExitResult(derivedWordBookId = null)
    }
}

/**
 * 设置 Fake：默认仅 PRONUNCIATION 段（每词单段，VM 层时序断言的最小确定形态）。
 * Phase 8 写路径：直写字段；[failWrites] = true 时全部 setter 抛校验异常
 * （SettingsViewModel 失败提示路径用，异常形态同真实仓储 RepositoryValidationException）。
 */
class FakeLearningSettingsRepository(
    var toggles: PlaybackToggles = PlaybackToggles(
        pronunciation = true,
        spelling = false,
        meaningEn = false,
        meaningCn = false,
        example = false,
        exampleCn = false,
    ),
    var groupSize: Int = 10,
    var commandWindowMs: Long = 4_000L,
    var ttsRate: Float = 1.0f,
    var ttsPitch: Float = 1.0f,
    var failWrites: Boolean = false,
) : LearningSettingsRepository {
    override suspend fun getGroupSize(): Int = groupSize
    override suspend fun getPlaybackToggles(): PlaybackToggles = toggles
    override suspend fun getCommandWindowMs(): Long = commandWindowMs
    override suspend fun getTtsRate(): Float = ttsRate
    override suspend fun getTtsPitch(): Float = ttsPitch

    override suspend fun setGroupSize(value: Int) {
        failIfRequested()
        groupSize = value
    }

    override suspend fun setPlaybackToggles(value: PlaybackToggles) {
        failIfRequested()
        toggles = value
    }

    override suspend fun setCommandWindowMs(value: Long) {
        failIfRequested()
        commandWindowMs = value
    }

    override suspend fun setTtsRate(value: Float) {
        failIfRequested()
        ttsRate = value
    }

    override suspend fun setTtsPitch(value: Float) {
        failIfRequested()
        ttsPitch = value
    }

    private fun failIfRequested() {
        if (failWrites) {
            throw RepositoryValidationException("测试注入的写入失败")
        }
    }
}

/** 内容 Fake：每词一个仅 PRONUNCIATION 可播的极简词条（释义/例句空，L2 空词场景之外的常规路径）。 */
class FakePlaybackContentRepository : PlaybackContentRepository {
    var textsByWordId: Map<Long, String> = emptyMap()

    override suspend fun getPlaybackContent(wordBookId: Long, wordId: Long): PlaybackContent? =
        textsByWordId[wordId]?.let { text ->
            PlaybackContent(
                word = Word(wordId = wordId, text = text, normalizedText = text.lowercase()),
                selectedDefinitions = emptyList(),
                examplesByDefinitionEntryId = emptyMap(),
            )
        }
}

/** 位置 Fake：记录 save/clear（恢复双源语义见 Step 2 TC-AE-18，此处观测持久化被触发）。 */
class FakePlaybackPositionRepository : PlaybackPositionRepository {
    var lastSaved: PlaybackPosition? = null
        private set
    var saveCount = 0
        private set
    var clearCount = 0
        private set

    override suspend fun save(position: PlaybackPosition) {
        saveCount++
        lastSaved = position
    }

    override suspend fun get(): PlaybackPosition? = null

    override suspend fun clear() {
        clearCount++
    }
}

/** 语音命令识别 Fake（Phase 5 Step 1，与 jvmTest 同语义）：脚本化结果立即出队；空脚本 = 静默至超时。 */
class FakeSpeechCommandRecognizer(
    initialAvailable: Boolean = false,
) : SpeechCommandRecognizer {
    override val isAvailable = MutableStateFlow(initialAvailable)
    val listenCalls = mutableListOf<Long>()

    /** 脚本结果队列（立即出队；耗尽后走静默 Timeout 分支）。 */
    val script = ArrayDeque<RecognitionResult>()
    var cancelledCount = 0
        private set

    override suspend fun listenOnce(windowMs: Long): RecognitionResult {
        listenCalls += windowMs
        val scripted = script.removeFirstOrNull()
        if (scripted != null) return scripted
        return try {
            delay(windowMs) // 模拟真实监听：窗口预算内挂起（被取消 = 关识别）
            RecognitionResult.Timeout
        } catch (e: CancellationException) {
            cancelledCount++
            throw e
        }
    }
}

/** 勋章仓储 Fake（Phase 6）：预置/按需授予 BOOK_COMPLETED，授予与查询可观测（手写 Fake，无 mock 框架）。 */
class FakeAchievementRepository : AchievementRepository {

    val grants = mutableListOf<Pair<Long, BookCompletedPayload>>()
    val medalsByBook = mutableMapOf<Long, Achievement>()

    override suspend fun grantBookCompleted(
        wordBookId: Long,
        payload: BookCompletedPayload,
    ): GrantAchievementResult {
        grants += wordBookId to payload
        medalsByBook[wordBookId]?.let { return GrantAchievementResult(it, firstGrant = false) }
        val granted = Achievement(
            achievementId = (medalsByBook.size + 1).toLong(),
            type = AchievementType.BOOK_COMPLETED,
            wordBookId = wordBookId,
            payload = payload,
            earnedAt = payload.finishedAt,
        )
        medalsByBook[wordBookId] = granted
        return GrantAchievementResult(granted, firstGrant = true)
    }

    override suspend fun getAchievements(): List<Achievement> =
        medalsByBook.values.sortedByDescending { it.earnedAt }

    override suspend fun getBookCompletedFor(wordBookId: Long): Achievement? = medalsByBook[wordBookId]
}

/** 测试快照助手：两词单组会话（groupSize=10 → groupIndex 0）。 */
object LearningSessionFixtures {
    const val SESSION_ID = 1L
    const val BOOK_ID = 9L
    const val WORD_BOOST = 101L
    const val WORD_ABANDON = 102L
    val STARTED_AT: Instant = Instant.parse("2026-09-06T00:00:00Z")

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        session = LearningSession(
            sessionId = SESSION_ID,
            wordBookId = BOOK_ID,
            status = SessionStatus.ACTIVE,
            groupSize = 10,
            startedAt = STARTED_AT,
        ),
        words = listOf(
            SessionWord(SESSION_ID, WORD_BOOST, groupIndex = 0, orderInGroup = 0, status = SessionWordStatus.PENDING),
            SessionWord(SESSION_ID, WORD_ABANDON, groupIndex = 0, orderInGroup = 1, status = SessionWordStatus.PENDING),
        ),
    )

    fun contentTexts(): Map<Long, String> = mapOf(
        WORD_BOOST to "boost",
        WORD_ABANDON to "abandon",
    )
}
