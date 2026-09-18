package com.vocabularybooster.playback

import com.vocabularybooster.DispatchersForTest
import com.vocabularybooster.FixedClock
import com.vocabularybooster.TestDb
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightPlaybackContentRepository
import com.vocabularybooster.data.SqlDelightPlaybackPositionRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import com.vocabularybooster.speech.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * PlaybackOrchestrator 重启续播集成（TC-AE-13 restart 段 / TC-AE-18 最后子句）：
 * 真实 SQLite 文件库 + 真实 playback.position KV + 真实 Q4/Q4b 内容读路径 + 真实引擎，
 * close/reopen 后新装配（新引擎新编排器，零内存态）按「SessionWord.PLAYING 词级真相 +
 * 双匹配 position 段级恢复」续播。词全开开关 = 6 段（PRON/SPELL/M_EN/M_CN/EX_AUDIO/EX_CN）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorRestartTest {

    private class RealHarness(db: TestDb, scope: CoroutineScope) {
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
        val content = SqlDelightPlaybackContentRepository(db.database, DispatchersForTest)
        val position = SqlDelightPlaybackPositionRepository(db.database, DispatchersForTest)
        val recognizer = FakeSpeechCommandRecognizer() // 默认不可用 = P4 纯倒计时形态（本文件语义不变）
        val orchestrator = PlaybackOrchestrator(
            engine = engine,
            contentRepository = content,
            positionRepository = position,
            settingsRepository = settings,
            audioPlayer = audio,
            synthesizer = tts,
            recognizer = recognizer,
            commandParser = CommandParser(),
            eventBus = DefaultDomainEventBus(),
            scope = scope,
        )
    }

    private data class SeededBook(val bookId: Long, val wordIds: List<Long>)

    /** 词 + 释义 + 例句裸插入，entry/选择行走生产保存事务（FR-5 单元）；词序 alpha → beta。 */
    private suspend fun TestDb.seedTwoWordsWithSelections(): SeededBook {
        val bookRepo = SqlDelightWordBookRepository(database, FixedClock(), DispatchersForTest)
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        val selections = mutableListOf<Pair<Long, DefinitionSelection>>()
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordBookQueries.insertOriginalWordBook("restart-book", null, now, now)
            bookId = database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            listOf("alpha", "beta").forEach { text ->
                database.wordQueries.insertWord(text, text, null, null, null, now, now)
                val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
                wordIds += wordId
                database.definitionEntryQueries.insertDefinitionEntry(wordId, "n", 0, 0, "meaning en $text", "中文释义 $text")
                val defId = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
                database.exampleQueries.insertExample(defId, "example sentence $text", "例句 $text", "TTS", null, null, null, null, 0)
                val exampleId = database.exampleQueries.selectExamplesForEntry(defId).executeAsList().single().exampleId
                selections += wordId to DefinitionSelection(defId, listOf(exampleId))
            }
        }
        selections.forEach { (wordId, selection) ->
            bookRepo.saveWordToBooks(SaveWordRequest(wordId = wordId, wordBookIds = listOf(bookId), selections = listOf(selection)))
        }
        return SeededBook(bookId, wordIds)
    }

    @Test
    fun crashRestartResumesFromPersistedWordAndSegmentPosition() = runTest {
        val db = TestDb.file()
        val seeded = db.seedTwoWordsWithSelections()
        val sessionId: Long
        try {
            val harnessA = RealHarness(db, backgroundScope)
            val started = assertIs<StartResult.Started>(harnessA.orchestrator.startSession(seeded.bookId))
            sessionId = started.snapshot.session.sessionId
            runCurrent() // 词 1 seg0 起播 → 真实 KV 写 position(seg0)
            advanceTimeBy(100)
            runCurrent() // seg1（SPELLING）起播 → position(seg1)
            harnessA.orchestrator.dispose() // 模拟进程死亡：驱动停止，库与 KV 保持
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val harnessB = RealHarness(reopened, backgroundScope)
            assertIs<ResumeResult.Resumed>(harnessB.orchestrator.resumeSession(sessionId))
            runCurrent()

            val playing = assertIs<PlaybackState.Playing>(harnessB.orchestrator.state.value)
            assertEquals(seeded.wordIds[0], playing.wordRef.wordId) // 词级真相 = 库内 PLAYING 词 1
            assertEquals(1, playing.segmentIndex) // 段级恢复 = KV position(seg1)
            assertEquals("a-l-p-h-a", harnessB.tts.requests.single().text) // seg1 = SPELLING 重读
        } finally {
            reopened.close()
        }
    }

    @Test
    fun advanceSwitchedPlayingWordWithStalePositionResumesNewWordFromZero() = runTest {
        val db = TestDb.file()
        val seeded = db.seedTwoWordsWithSelections()
        val sessionId: Long
        try {
            val harness = RealHarness(db, backgroundScope)
            val started = assertIs<StartResult.Started>(harness.engine.startSession(seeded.bookId))
            sessionId = started.snapshot.session.sessionId
            assertIs<AdvanceResult.NextWord>(harness.engine.advance(sessionId)) // 词 1 PLAYING
            // 陈旧段级信息：仍指向词 1 的 seg3
            harness.position.save(
                PlaybackPosition(sessionId, seeded.wordIds[0], segmentIndex = 3, offsetMs = 500L, phase = PlaybackPhase.PLAYING),
            )
            // 词级真相已换：advance 迁移 PLAYING → 词 2（position 未跟上，即 L3 冲突场景）
            assertIs<AdvanceResult.NextWord>(harness.engine.advance(sessionId))
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val harnessB = RealHarness(reopened, backgroundScope)
            assertIs<ResumeResult.Resumed>(harnessB.orchestrator.resumeSession(sessionId))
            runCurrent()

            val playing = assertIs<PlaybackState.Playing>(harnessB.orchestrator.state.value)
            assertEquals(seeded.wordIds[1], playing.wordRef.wordId) // 新 PLAYING 词 2
            assertEquals(0, playing.segmentIndex) // 陈旧 position 被忽略，从 seg0
            assertEquals("beta", harnessB.tts.requests.single().text)
        } finally {
            reopened.close()
        }
    }
}
