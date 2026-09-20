package com.vocabularybooster.playback

import com.vocabularybooster.DispatchersForTest
import com.vocabularybooster.FixedClock
import com.vocabularybooster.TestDb
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.model.DefinitionEntry
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import com.vocabularybooster.speech.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PlaybackOrchestrator 状态机测试（AUDIO_ENGINE_SPEC §3/§9，TC-AE-03/07/08/09/11/12/13/14/17/18/19）：
 * 真实 LearningEngine + 真实 SQLite（学习侧语义 = 库中真相）+ 假播放端口（段时长 100ms 虚拟时间）。
 * 标准词全开开关 = 6 段：[PRON, SPELL, MEANING_EN, MEANING_CN, EXAMPLE_AUDIO, EXAMPLE_CN]。
 * 时序：6×100ms 段 + 300ms guard + 4000ms 窗口（虚拟时间逐格推进，不用 advanceUntilIdle——
 * 会话组内回绕永不空闲，会挂死）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorStateTest {

    /** 真实引擎 + 真库 + 假端口/设置/内容/位置 的装配。 */
    private class Harness(db: TestDb, scope: CoroutineScope) {
        val clock = FixedClock()
        val sessionRepo = SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
        val settings = FakeLearningSettingsRepository()
        val engine = DefaultLearningEngine(
            sessionRepository = sessionRepo,
            settingsRepository = settings,
            masteryMarker = MasteryMarker(sessionRepo, clock),
            wordBookDeriver = WordBookDeriver(
                wordBookRepository = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest),
                clock = clock,
            ),
        )
        val tts = FakeSpeechSynthesizer()
        val audio = FakeAudioPlayer()
        val content = FakePlaybackContentRepository()
        val position = FakePlaybackPositionRepository()
        val recognizer = FakeSpeechCommandRecognizer() // 默认不可用 = P4 纯倒计时形态（本文件语义不变）
        val eventBus = DefaultDomainEventBus() // Phase 6：完成事件（TC-AC-05 用例订阅）
        val orchestrator = PlaybackOrchestrator(
            engine = engine,
            contentRepository = content,
            positionRepository = position,
            settingsRepository = settings,
            audioPlayer = audio,
            synthesizer = tts,
            recognizer = recognizer,
            commandParser = CommandParser(),
            eventBus = eventBus,
            scope = scope,
        )
    }

    private fun standardContent(wordId: Long, text: String, withAudio: Boolean = false): PlaybackContent {
        val definition = DefinitionEntry(
            definitionEntryId = wordId * 10 + 1, wordId = wordId, partOfSpeech = "n",
            partOfSpeechOrder = 0, definitionOrder = 0,
            meaningEN = "meaning en $text", meaningCN = "中文释义 $text",
        )
        val example = Example(
            exampleId = wordId * 100 + 1, definitionEntryId = definition.definitionEntryId,
            sentence = "example sentence $text", chineseTranslation = "例句 $text",
            sourceType = ExampleSourceType.TTS,
            audioUri = if (withAudio) "https://audio/$text.mp3" else null, exampleOrder = 0,
        )
        return PlaybackContent(Word(wordId, text, text), listOf(definition), mapOf(definition.definitionEntryId to listOf(example)))
    }

    private fun TestDb.seedBook(labels: List<String>): Pair<Long, List<Long>> {
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordBookQueries.insertOriginalWordBook("pb-book", null, now, now)
            bookId = database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            labels.forEachIndexed { index, label ->
                database.wordQueries.insertWord(label, label, null, null, null, now, now)
                val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
                wordIds += wordId
                database.wordBookEntryQueries.insertEntry(bookId, wordId, index.toLong(), null, now)
            }
        }
        return bookId to wordIds
    }

    private fun Harness.registerWords(bookId: Long, wordIds: List<Long>, texts: List<String>, withAudio: Boolean = false) {
        wordIds.forEachIndexed { index, id ->
            content.contents[bookId to id] = standardContent(id, texts[index], withAudio)
        }
    }

    /** 标准词文本（与 [standardContent] 段文本一致）。 */
    private val alphaTexts = listOf("alpha", "a-l-p-h-a", "meaning en alpha", "中文释义 alpha", "example sentence alpha", "例句 alpha")

    // —— TC-AE-03：末段完成 → 300ms guard → 窗口开启；倒计时归零 → advance（L1 纯倒计时）——

    @Test
    fun lastSegmentThenGuardThenWindowThenAdvanceToNextWord() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        // 6 段播完（600ms）→ guard 期间仍 Playing；299ms 不足，+1ms 窗口开启
        advanceTimeBy(600)
        runCurrent()
        assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        advanceTimeBy(299)
        runCurrent()
        assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        advanceTimeBy(1)
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(wordIds[0], window.wordRef.wordId)
        assertEquals(4_000L, window.totalMs)
        assertEquals(4_000L, window.remainingMs)

        // 倒计时递减（TC-AE-03 倒计时断言）
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3_000L, assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value).remainingMs)

        // 窗口耗尽 → advance → 下一词 seg0；词 1 不因窗口超时被掌握（引擎侧裁决）
        // 窗 1 于 t=4900 结束 → advance → 词 2 seg0 占 [4900,5000)：推进到 4950 恰在段内
        advanceTimeBy(3_050)
        runCurrent()
        val playing2 = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[1], playing2.wordRef.wordId)
        assertEquals(0, playing2.segmentIndex)
        assertEquals(7, h.tts.requests.size) // 词 1 六段 + 词 2 PRON 起播
        assertEquals("beta", h.tts.requests.last().text)
        assertEquals(SessionWordStatus.PENDING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status)
        assertEquals(SessionWordStatus.PLAYING, h.sessionRepo.getSessionWord(1L, wordIds[1])!!.status)
        assertEquals(2, h.sessionRepo.countUnmasteredEntries(bookId))
    }

    @Test
    fun commandWindowDurationComesFromSetting() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        h.settings.commandWindowMs = 6_000L // L1/L6：窗口时长取设置（非默认 4000）
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        advanceTimeBy(600 + 300)
        runCurrent()
        assertEquals(6_000L, assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value).totalMs)

        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(3_500L, assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value).remainingMs)
    }

    // —— TC-AE-07：Pause/Resume 段粒度（TTS 重读本段；文件段 offsetMs 精确续播）——

    @Test
    fun pauseDuringTtsSegmentResumesByReplayingSameSegment() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(200) // seg0/seg1 完成，seg2（MEANING_EN，TTS 段）播放中
        runCurrent()
        assertEquals(2, assertIs<PlaybackState.Playing>(h.orchestrator.state.value).segmentIndex)

        h.orchestrator.pause()
        val paused = assertIs<PlaybackState.Paused>(h.orchestrator.state.value)
        assertEquals(2, paused.segmentIndex)
        assertEquals(0L, paused.offsetMs) // TTS 段无 offset
        assertEquals(wordIds[0], paused.wordRef.wordId)

        h.orchestrator.resume()
        runCurrent()
        val replayed = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(2, replayed.segmentIndex) // 段索引不变、词索引不变（重读本段，绝不整词重播）
        assertEquals(1, h.tts.stopCount)
        // 重读本段 = 该段文本第二次 speak
        assertEquals(2, h.tts.requests.count { it.text == alphaTexts[2] })
        h.orchestrator.dispose()
    }

    @Test
    fun pauseDuringFileSegmentResumesAtExactOffset() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"), withAudio = true) // seg4 = 文件段
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(400) // seg0..3 完成，seg4（EXAMPLE_AUDIO）播放中
        runCurrent()
        assertIs<PlaybackState.Playing>(h.orchestrator.state.value).let { assertEquals(4, it.segmentIndex) }

        h.audio.pauseOffsetMs = 1_234L
        h.orchestrator.pause()
        val paused = assertIs<PlaybackState.Paused>(h.orchestrator.state.value)
        assertEquals(4, paused.segmentIndex)
        assertEquals(1_234L, paused.offsetMs) // pause() 回传 offset 记录在状态与位置
        assertEquals(PlaybackPhase.PLAYING, h.position.saves.last().phase)
        assertEquals(1_234L, h.position.saves.last().offsetMs)

        h.orchestrator.resume()
        runCurrent()
        assertEquals(listOf(0L, 1_234L), h.audio.playAtOffsets) // playAt(0) 后精确 playAt(1234)
        assertEquals(4, assertIs<PlaybackState.Playing>(h.orchestrator.state.value).segmentIndex)
        assertTrue(h.tts.requests.none { it.text == alphaTexts[4] }) // 文件段成功不触发 TTS
        h.orchestrator.dispose()
    }

    // —— TC-AE-08：Pause/Resume 于窗口态 → 重开整窗 ——

    @Test
    fun pauseDuringWindowReopensFullWindowOnResume() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(600 + 300 + 1_000)
        runCurrent()
        assertEquals(3_000L, assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value).remainingMs)

        h.orchestrator.pause()
        val paused = assertIs<PlaybackState.Paused>(h.orchestrator.state.value)
        assertTrue(paused.atCommandWindow)

        h.orchestrator.resume()
        runCurrent()
        val reopened = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(4_000L, reopened.remainingMs) // 重开整窗（非剩余 3000）
        assertEquals(4_000L, reopened.totalMs)
        h.orchestrator.dispose()
    }

    // —— TC-AE-09：Next / Replay 不改掌握；词级重置 ——

    @Test
    fun nextIsPureJumpWithoutMastery() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        h.orchestrator.next()
        runCurrent()
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[1], playing.wordRef.wordId)
        assertEquals(0, playing.segmentIndex)
        // FR-11：纯跳转不改掌握——词 1 回 PENDING、零 WordMastery（Q3 不减）
        assertEquals(SessionWordStatus.PENDING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status)
        assertEquals(SessionWordStatus.PLAYING, h.sessionRepo.getSessionWord(1L, wordIds[1])!!.status)
        assertEquals(2, h.sessionRepo.countUnmasteredEntries(bookId))
        h.orchestrator.dispose()
    }

    @Test
    fun replayRestartsCurrentWordFromSegmentZeroAndVoidWindow() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(300) // seg0..2 完成、seg3 播放中
        runCurrent()

        h.orchestrator.replay()
        runCurrent()
        val replaying = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[0], replaying.wordRef.wordId)
        assertEquals(0, replaying.segmentIndex)
        assertEquals(5, h.tts.requests.size) // seg0..2 完成 + 被打断的 seg3 + 重播 PRON
        assertEquals(alphaTexts[0], h.tts.requests.last().text)
        assertEquals(SessionWordStatus.PLAYING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status) // 不动引擎状态

        // 窗口中 replay → 窗口作废（词重播而非 advance）
        advanceTimeBy(600 + 300) // 重播词 6 段 + guard
        runCurrent()
        assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        h.orchestrator.replay()
        runCurrent()
        assertEquals(0, assertIs<PlaybackState.Playing>(h.orchestrator.state.value).segmentIndex)
        advanceTimeBy(600 + 300)
        runCurrent()
        assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value) // 仍是词 1 的窗口
        assertEquals(wordIds[0], assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value).wordRef.wordId)
        h.orchestrator.dispose()
    }

    // —— TC-AE-11：双语逐段切换 + rate/pitch 取设置（SPELLING 0.8×）——

    @Test
    fun speakRequestsCarryLangRatePitchPerSegment() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        h.settings.ttsRate = 1.5f
        h.settings.ttsPitch = 0.9f
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        val requests = h.tts.requests
        assertEquals(5, requests.size) // seg0..3 完成 + seg4 起播（起播即记录请求）
        assertEquals("en-US", requests[0].lang.tag) // PRONUNCIATION
        assertEquals(1.5f, requests[0].rate)
        assertEquals("en-US", requests[1].lang.tag) // SPELLING：0.8 × 1.5 = 1.2
        assertEquals(1.2f, requests[1].rate)
        assertEquals("zh-CN", requests[3].lang.tag) // MEANING_CN
        assertEquals(1.5f, requests[3].rate)
        assertTrue(requests.all { it.pitch == 0.9f })
        h.orchestrator.dispose()
    }

    // —— TC-AE-12（L7 P4 子集）：音频失败 → TTS 兜底；音频成功不碰 TTS；TTS 失败 → Paused(error) ——

    @Test
    fun audioLoadFailureFallsBackToTtsAndSessionContinues() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"), withAudio = true)
        h.audio.failPrepare = true
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        // seg4（EXAMPLE_AUDIO）prepare 失败 → TTS 朗读 sentence 兜底 + 降级标志
        val degraded = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertTrue(degraded.degraded)
        assertEquals(5, h.tts.requests.size)
        assertEquals(alphaTexts[4], h.tts.requests.last().text)
        assertEquals(1, h.audio.preparedTracks.size)

        // 会话不中断：词继续走完 → 窗口 → 下一词
        advanceTimeBy(100 + 100 + 300)
        runCurrent()
        assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        h.orchestrator.dispose()
    }

    @Test
    fun audioPlayFailureAlsoFallsBackToTts() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"), withAudio = true)
        h.audio.failPlay = true
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        assertTrue(assertIs<PlaybackState.Playing>(h.orchestrator.state.value).degraded)
        assertEquals(alphaTexts[4], h.tts.requests.last().text)
        assertEquals(listOf(0L), h.audio.playAtOffsets) // playAt 尝试过并失败
        h.orchestrator.dispose()
    }

    @Test
    fun ttsFailurePausesWithErrorAndPreservesSession() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        h.tts.failTexts += alphaTexts[2] // MEANING_EN 段 TTS 失败注入
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        val failed = assertIs<PlaybackState.Paused>(h.orchestrator.state.value)
        assertEquals(PlaybackError.TTS_FAILED, failed.error)
        assertEquals(2, failed.segmentIndex)
        assertEquals(SessionStatus.ACTIVE, h.sessionRepo.getSession(1L)!!.status) // 会话不动
        assertEquals(PlaybackPhase.PLAYING, h.position.saves.last().phase) // 位置已存于段起播

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, h.tts.requests.size) // 失败后驱动停止（零在途 speak）
        assertIs<PlaybackState.Paused>(h.orchestrator.state.value)

        h.tts.failTexts.clear() // 修复后 resume 重读本段继续
        h.orchestrator.resume()
        runCurrent()
        assertEquals(4, h.tts.requests.size)
        assertEquals(2, assertIs<PlaybackState.Playing>(h.orchestrator.state.value).segmentIndex)
        h.orchestrator.dispose()
    }

    // —— TC-AE-17（裁决 L2，negative）：空段词绝不 MASTERED——直通窗口 → advance ——

    @Test
    fun emptySegmentWordNeverMarksMasteryAndRoutesThroughWindow() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.content.contents[bookId to wordIds[1]] = standardContent(wordIds[1], "beta")
        // 词 1 无内容登记 → getPlaybackContent = null → 空词（L2 输入形态）
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        // 空词：无 guard 可守 → 直接 CommandWindow；零 speak、零文件播放
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(wordIds[0], window.wordRef.wordId)
        assertTrue(h.tts.requests.isEmpty())
        assertTrue(h.audio.preparedTracks.isEmpty())
        assertEquals(SessionWordStatus.PLAYING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status)

        advanceTimeBy(4_000)
        runCurrent()
        // 窗口结束 advance → 词 2 正常播放；词 1 回 PENDING（绝不 MASTERED）
        val playing2 = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[1], playing2.wordRef.wordId)
        assertEquals(SessionWordStatus.PENDING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status)
        assertEquals(2, h.sessionRepo.countUnmasteredEntries(bookId)) // 零 WordMastery
        assertEquals(1, h.tts.requests.size) // 仅词 2 的 PRON
        h.orchestrator.dispose()
    }

    @Test
    fun allTogglesOffMidSessionYieldsEmptyWordCycleWithoutMastery() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        h.settings.toggles = PlaybackToggles(
            pronunciation = false, spelling = false, meaningEn = false,
            meaningCn = false, example = false, exampleCn = false,
        ) // 会话已开始（开始时全开通过拒绝检查），段评估时全关 → 词 1 空段（L2）
        runCurrent()

        advanceTimeBy(300 + 2_000)
        runCurrent()
        assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertTrue(h.tts.requests.isEmpty()) // 空词零播放

        // 窗口中恢复开关 → 窗口结束 advance 后词 2 正常播放（L4 段级重读的极端形态）
        h.settings.toggles = PlaybackToggles.DEFAULT
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(wordIds[1], assertIs<PlaybackState.Playing>(h.orchestrator.state.value).wordRef.wordId)
        assertEquals(SessionWordStatus.PENDING, h.sessionRepo.getSessionWord(1L, wordIds[0])!!.status)
        assertEquals(2, h.sessionRepo.countUnmasteredEntries(bookId))
        h.orchestrator.dispose()
    }

    // —— TC-AE-18（裁决 L3）：恢复双源——词级真相 SessionWord.PLAYING，position 仅段级 ——

    @Test
    fun resumeAppliesMatchingPositionSegmentAndOffset() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"), withAudio = true)
        val started = assertIs<StartResult.Started>(h.engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        h.engine.advance(sessionId) // 库内事实：词 1 PLAYING
        h.position.current = PlaybackPosition(sessionId, wordIds[0], segmentIndex = 4, offsetMs = 2_222L, phase = PlaybackPhase.PLAYING)

        assertIs<ResumeResult.Resumed>(h.orchestrator.resumeSession(sessionId))
        runCurrent()

        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[0], playing.wordRef.wordId)
        assertEquals(4, playing.segmentIndex) // 恢复到 segmentIndex
        assertEquals(SegmentType.EXAMPLE_AUDIO, playing.segment!!.type)
        assertEquals(listOf(2_222L), h.audio.playAtOffsets) // 文件段恢复 offsetMs
        assertEquals(0, h.tts.requests.size) // 恢复点之前/之外的段零 speak
        h.orchestrator.dispose()
    }

    @Test
    fun resumeIgnoresPositionOfDifferentWordOrNonexistentWord() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        val started = assertIs<StartResult.Started>(h.engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        h.engine.advance(sessionId)

        // position 指向词 2（库内 PLAYING = 词 1）→ 忽略，从词 1 seg0
        h.position.current = PlaybackPosition(sessionId, wordIds[1], segmentIndex = 3, offsetMs = 999L, phase = PlaybackPhase.PLAYING)
        assertIs<ResumeResult.Resumed>(h.orchestrator.resumeSession(sessionId))
        runCurrent()
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[0], playing.wordRef.wordId)
        assertEquals(0, playing.segmentIndex)
        assertEquals(alphaTexts[0], h.tts.requests.single().text)
        h.orchestrator.dispose()
    }

    @Test
    fun resumeIgnoresPositionOfDifferentWordPointerVariant() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        val started = assertIs<StartResult.Started>(h.engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        h.engine.advance(sessionId)

        h.position.current = PlaybackPosition(sessionId, wordId = 999_999L, segmentIndex = 5, offsetMs = 77L, phase = PlaybackPhase.PLAYING)
        assertIs<ResumeResult.Resumed>(h.orchestrator.resumeSession(sessionId))
        runCurrent()
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordIds[0], playing.wordRef.wordId)
        assertEquals(0, playing.segmentIndex)
        assertEquals(alphaTexts[0], h.tts.requests.single().text)
        h.orchestrator.dispose()
    }

    @Test
    fun staleSessionPositionIsIgnoredOnFreshStart() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        // 旧会话的 position（sessionId 不匹配，即使 wordId 相同）→ 新会话从 seg0
        h.position.current = PlaybackPosition(sessionId = 999L, wordIds[0], segmentIndex = 3, offsetMs = 500L, phase = PlaybackPhase.PLAYING)

        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(0, playing.segmentIndex)
        assertEquals(alphaTexts[0], h.tts.requests.single().text)
        h.orchestrator.dispose()
    }

    @Test
    fun windowPhasePositionReopensWindowDirectlyWithoutReplaying() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        val started = assertIs<StartResult.Started>(h.engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        h.engine.advance(sessionId)
        // 存档相位 = WINDOW（词已播完、窗口中断）→ 重开整窗，不重播已播段
        h.position.current = PlaybackPosition(sessionId, wordIds[0], segmentIndex = 6, offsetMs = 0L, phase = PlaybackPhase.WINDOW)

        assertIs<ResumeResult.Resumed>(h.orchestrator.resumeSession(sessionId))
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(wordIds[0], window.wordRef.wordId)
        assertEquals(4_000L, window.totalMs)
        assertTrue(h.tts.requests.isEmpty())
        h.orchestrator.dispose()
    }

    @Test
    fun resumeSessionPropagatesBookDeletedAndStaysIdle() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        val started = assertIs<StartResult.Started>(h.engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        h.orchestrator.dispose()
        db.database.wordBookQueries.deleteWordBook(bookId) // JDBC 默认 FK off：书删悬挂态可达

        val result = h.orchestrator.resumeSession(sessionId)
        assertEquals(ResumeResult.Reason.BOOK_DELETED, assertIs<ResumeResult.Rejected>(result).reason)
        assertIs<PlaybackState.Idle>(h.orchestrator.state.value)
    }

    // —— TC-AE-13：位置持久化生命周期（段切换/窗口写入；结束清除）——

    @Test
    fun positionLifecycleSavesPerSegmentWindowAndClearsOnCompletion() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha"))
        h.registerWords(bookId, wordIds, listOf("alpha"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        advanceTimeBy(600 + 300)
        runCurrent()
        // 词内 6 段起播各写一次 PLAYING(0..5)，进窗口写 WINDOW(6)
        assertEquals((0..5).map { PlaybackPosition(1L, wordIds[0], it, 0L, PlaybackPhase.PLAYING) }, h.position.saves.take(6))
        assertEquals(PlaybackPosition(1L, wordIds[0], 6, 0L, PlaybackPhase.WINDOW), h.position.saves[6])

        // 窗口中经引擎掌握（P5 语音路径占位，与编排器无关）→ 窗口耗尽 advance → BookComplete
        assertIs<MasteryResult.Marked>(h.engine.markMastered(1L, wordIds[0], MasterySource.VOICE))
        advanceTimeBy(4_000)
        runCurrent()

        assertIs<PlaybackState.Completed>(h.orchestrator.state.value)
        assertEquals(SessionStatus.COMPLETED, h.sessionRepo.getSession(1L)!!.status)
        assertNull(h.position.current) // §5：会话结束清除键
        assertEquals(1, h.position.clearCount)
        assertTrue(h.tts.stopCount >= 1 && h.audio.stopCount >= 1) // TC-AE-14：先停端口再暴露终态
    }

    // —— TC-AE-14：exit 停止次序 + 终态幂等 ——

    @Test
    fun exitStopsPortsClearsPositionAndIsIdempotent() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        val exitResult = h.orchestrator.exit()
        assertNull(exitResult.derivedWordBookId) // 零掌握 = 分支 A：不派生
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertEquals(SessionStatus.ABANDONED, h.sessionRepo.getSession(1L)!!.status)
        assertNull(h.position.current)
        assertTrue(h.tts.stopCount >= 1 && h.audio.stopCount >= 1)
        assertTrue(h.tts.concurrent == 0) // 零在途 speak
        val endedAt = h.sessionRepo.getSession(1L)!!.endedAt

        // 终态重入：全部控制幂等 no-op，endedAt 不刷新
        assertNull(h.orchestrator.exit().derivedWordBookId)
        h.orchestrator.pause()
        h.orchestrator.resume()
        h.orchestrator.next()
        h.orchestrator.replay()
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertEquals(endedAt, h.sessionRepo.getSession(1L)!!.endedAt)
        assertEquals(3, h.tts.requests.size) // 退出后零新增 speak（200ms = 3 段）
    }

    @Test
    fun exitOnCompletedSessionReturnsNullWithoutStateChange() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha"))
        h.registerWords(bookId, wordIds, listOf("alpha"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        assertIs<MasteryResult.Marked>(h.engine.markMastered(1L, wordIds[0], MasterySource.BUTTON))
        h.orchestrator.exit() // 分支 C：Q3=0 → COMPLETED

        assertEquals(SessionStatus.COMPLETED, h.sessionRepo.getSession(1L)!!.status)
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertNull(h.orchestrator.exit().derivedWordBookId) // 幂等
        assertEquals(SessionStatus.COMPLETED, h.sessionRepo.getSession(1L)!!.status)
    }

    // —— TC-AC-05：完成事件发布锚点（ACHIEVEMENT_SPEC v1.1 §3：先停端口、后发事件；两条完成路径）——

    @Test
    fun advanceCompletionPublishesEventAfterPortsStopped() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha"))
        h.registerWords(bookId, wordIds, listOf("alpha"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        val events = mutableListOf<DomainEvent>()
        val stopsAtEvent = mutableListOf<Pair<Int, Int>>()
        backgroundScope.launch {
            h.eventBus.events.collect {
                events += it
                stopsAtEvent += h.tts.stopCount to h.audio.stopCount
            }
        }

        advanceTimeBy(600 + 300) // 6 段 + guard → 窗口
        runCurrent()
        assertIs<MasteryResult.Marked>(h.engine.markMastered(1L, wordIds[0], MasterySource.VOICE)) // 快照全掌握
        advanceTimeBy(4_000) // 窗口耗尽 → advance → BookComplete → finishAsCompleted
        runCurrent()

        val event = assertIs<DomainEvent.WordBookCompleted>(events.single())
        assertEquals(1L, event.sessionId)
        assertEquals(bookId, event.wordBookId)
        val (ttsStops, audioStops) = stopsAtEvent.single()
        assertTrue(ttsStops >= 1 && audioStops >= 1, "事件到达时端口必须已停：tts=$ttsStops audio=$audioStops")
        assertIs<PlaybackState.Completed>(h.orchestrator.state.value)
    }

    @Test
    fun exitBranchCPublishesEventAfterPortsStopped() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha"))
        h.registerWords(bookId, wordIds, listOf("alpha"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        val events = mutableListOf<DomainEvent>()
        val stopsAtEvent = mutableListOf<Pair<Int, Int>>()
        backgroundScope.launch {
            h.eventBus.events.collect {
                events += it
                stopsAtEvent += h.tts.stopCount to h.audio.stopCount
            }
        }

        assertIs<MasteryResult.Marked>(h.engine.markMastered(1L, wordIds[0], MasterySource.BUTTON))
        val exitResult = h.orchestrator.exit() // 分支 C：Q3=0 → COMPLETED → NonCancellable 发布
        assertEquals(true, exitResult.sessionCompleted)
        runCurrent()

        assertIs<DomainEvent.WordBookCompleted>(events.single()).let {
            assertEquals(1L, it.sessionId)
            assertEquals(bookId, it.wordBookId)
        }
        val (ttsStops, audioStops) = stopsAtEvent.single()
        assertTrue(ttsStops >= 1 && audioStops >= 1, "事件到达时端口必须已停：tts=$ttsStops audio=$audioStops")
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertEquals(SessionStatus.COMPLETED, h.sessionRepo.getSession(1L)!!.status)
    }

    // —— TC-AE-19（裁决 L4）：Toggle 下一 Segment 生效，当前段不受影响 ——

    @Test
    fun toggleChangeAppliesToNextSegmentOnly() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()
        advanceTimeBy(100)
        runCurrent() // seg1（SPELLING）播放中

        h.settings.toggles = PlaybackToggles.DEFAULT.copy(meaningEn = false) // 段 N 播放中变更

        advanceTimeBy(100)
        runCurrent()
        // 段 N（SPELLING）原样完成：仅一次 speak、无重播/打断；段 N+1 跳过 MEANING_EN 直取 MEANING_CN
        assertEquals(1, h.tts.requests.count { it.text == alphaTexts[1] })
        val nextPlaying = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(SegmentType.MEANING_CN, nextPlaying.segment!!.type)
        assertEquals(listOf(alphaTexts[0], alphaTexts[1], alphaTexts[3]), h.tts.requests.map { it.text })
        h.orchestrator.dispose()
    }

    // —— 并发安全（§十三）：并发控制风暴不产生双播放流/非法状态 ——

    @Test
    fun concurrentControlsMaintainSinglePlaybackFlow() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        val storm = (0 until 30).map { i ->
            launch {
                repeat(3) {
                    when (i % 3) {
                        0 -> h.orchestrator.pause()
                        1 -> h.orchestrator.resume()
                        else -> h.orchestrator.next()
                    }
                }
            }
        }
        storm.joinAll()
        repeat(5) { runCurrent() }

        // 状态机完整性：合法状态之一；至多一个 PLAYING 驱动（假件并发计数）；库内至多一个 PLAYING 词
        assertTrue(
            h.orchestrator.state.value.let {
                it is PlaybackState.Playing || it is PlaybackState.Paused || it is PlaybackState.CommandWindow ||
                    it is PlaybackState.Idle
            },
        )
        assertTrue(h.tts.maxConcurrent <= 1)
        assertTrue(h.sessionRepo.getSessionWords(1L).count { it.status == SessionWordStatus.PLAYING } <= 1)
        h.orchestrator.dispose()
    }

    // —— 会话开始拒绝传播（编排器保持 Idle，零播放）——

    @Test
    fun startSessionRejectionPropagatesAndStaysIdle() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        h.settings.toggles = PlaybackToggles.DEFAULT.copy(
            pronunciation = false, spelling = false, meaningEn = false,
            meaningCn = false, example = false, exampleCn = false,
        )

        val rejected = assertIs<StartResult.Rejected>(h.orchestrator.startSession(bookId))
        assertEquals(StartResult.Reason.PLAYBACK_DISABLED, rejected.reason)
        assertIs<PlaybackState.Idle>(h.orchestrator.state.value)
        assertTrue(h.tts.requests.isEmpty())
        assertNull(h.sessionRepo.getActiveSession())
    }

    // —— FR-22 发音口音：英文段按设置映射 en-GB；切换下一 Segment 生效（对齐 L4 TC-AE-19）——

    @Test
    fun englishSegmentsSpeakConfiguredAccentAndSwitchAppliesNextSegment() = runTest {
        val db = TestDb.inMemory()
        val h = Harness(db, backgroundScope)
        val (bookId, wordIds) = db.seedBook(listOf("alpha", "beta"))
        h.registerWords(bookId, wordIds, listOf("alpha", "beta"))
        h.settings.ttsAccent = Lang.EN_GB // 开局英音
        assertIs<StartResult.Started>(h.orchestrator.startSession(bookId))
        runCurrent()

        // 词 1 全六段：英文四段 EN_GB、中文两段 ZH_CN 不受影响
        advanceTimeBy(600)
        runCurrent()
        assertEquals(
            listOf(Lang.EN_GB, Lang.EN_GB, Lang.EN_GB, Lang.ZH_CN, Lang.EN_GB, Lang.ZH_CN),
            h.tts.requests.map { it.lang },
        )

        // 窗口中切回美音 → 下一词首段（下一 Segment）即美音，无需重建会话
        h.settings.ttsAccent = Lang.EN_US
        advanceTimeBy(300 + 4_000) // guard + 窗口耗尽 → advance → 词 2 seg0 起播
        runCurrent()
        assertEquals(7, h.tts.requests.size)
        assertEquals(Lang.EN_US, h.tts.requests[6].lang)
        h.orchestrator.dispose()
    }
}
