package com.vocabularybooster.app.audio

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightPlaybackContentRepository
import com.vocabularybooster.data.SqlDelightPlaybackPositionRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.playback.PlaybackState
import com.vocabularybooster.playback.SegmentType
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.platform.Media3AudioPlayer
import com.vocabularybooster.platform.TtsSpeechSynthesizer
import com.vocabularybooster.speech.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 编排器 ↔ 真实 Android 播放后端 冒烟（Phase 4 Step 3，需模拟器）：
 * 全链路 = PlaybackContent → SegmentBuilder → PlaybackOrchestrator → Media3AudioPlayer / TtsSpeechSynthesizer，
 * 真实种子数据 + in-memory SQLite + 真实引擎栈（DI 同构装配，唯一一组端口实例）。
 *
 * - Smoke A：带占位 audioUri 的 Example → 文件段真实出声（progressMs 前进）→ 完成 → CommandWindow。
 * - Smoke B：audioUri 指向不存在资源 → Media3 真失败 → degraded 标志 → TTS 兜底 → 会话不中断。
 * - Smoke C：文件段 pause（真实 offsetMs）→ resume 续播（耗时可区分整段重播）。
 * - Smoke D：无 audioUri 的 Example（纯 TTS 段）→ pause → TTS 引擎层面真停。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock: Clock = Clock.System

    private lateinit var driver: AndroidSqliteDriver
    private lateinit var database: VocabularyDatabase
    private lateinit var audioPlayer: Media3AudioPlayer
    private lateinit var synthesizer: TtsSpeechSynthesizer
    private lateinit var orchestrator: PlaybackOrchestrator
    private lateinit var wordBookRepository: SqlDelightWordBookRepository
    private lateinit var wordRepository: SqlDelightWordRepository
    private lateinit var scope: CoroutineScope
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // Android 12+ 焦点授予与前台状态绑定：无 Activity 的插桩进程会被拒焦——启动真实 Activity 对齐前台场景
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Phase 5 Step 1：真实识别后端入栈（RECORD_AUDIO 先授予——真实 listenOnce 监听路径回归；
        // 若环境无识别服务则 isAvailable=false，编排器自动降级 P4 纯倒计时，既有断言不受影响）
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
        )
        runBlocking {
        withContext(Dispatchers.Main.immediate) {
            driver = AndroidSqliteDriver(VocabularyDatabase.Schema, context) // in-memory（name=null）
            database = VocabularyDatabase(driver)
            audioPlayer = Media3AudioPlayer(context)
            synthesizer = TtsSpeechSynthesizer(context)
            val settings = SqlDelightLearningSettingsRepository(database)
            val sessions = SqlDelightLearningSessionRepository(database, clock)
            val books = SqlDelightWordBookRepository(database, clock)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            orchestrator = PlaybackOrchestrator(
                engine = DefaultLearningEngine(
                    sessionRepository = sessions,
                    settingsRepository = settings,
                    masteryMarker = MasteryMarker(repository = sessions, clock = clock),
                    wordBookDeriver = WordBookDeriver(wordBookRepository = books, clock = clock),
                ),
                contentRepository = SqlDelightPlaybackContentRepository(database),
                positionRepository = SqlDelightPlaybackPositionRepository(database),
                settingsRepository = settings,
                audioPlayer = audioPlayer,
                synthesizer = synthesizer,
                recognizer = AndroidSpeechCommandRecognizer(context),
                commandParser = CommandParser(),
                eventBus = DefaultDomainEventBus(),
                scope = scope,
            )
            wordBookRepository = books
            wordRepository = SqlDelightWordRepository(database)
            SeedImporter(database, clock).ensureSeeded(SeedDictionaryProvider(SEED_DICTIONARY_JSON))
            // fixture 设置（直写 AppSetting——设置页写入属 Phase 8，端口只读）：
            // 窗口缩短加速冒烟；仅保留 EXAMPLE 段（单段定位被测路径）
            database.appSettingQueries.upsertSetting("settings.commandWindowMs", "800")
            database.appSettingQueries.upsertSetting(
                "settings.playbackToggles",
                """{"pronunciation":false,"spelling":false,"meaningEn":false,"meaningCn":false,"example":true,"exampleCn":false}""",
            )
        }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            scope.cancel()
            withContext(Dispatchers.Main.immediate) {
                audioPlayer.release()
                synthesizer.release()
            }
            driver.close()
        }
        scenario.close()
    }

    @Test
    fun smokeA_fileAudioSegment_playsThroughMedia3_andCompletes(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val bookId = bookWithBoostPlaceholderExample()
            assertTrue(orchestrator.startSession(bookId) is StartResult.Started)

            val playing = awaitState { it is PlaybackState.Playing } as PlaybackState.Playing
            assertEquals(SegmentType.EXAMPLE_AUDIO, playing.segment?.type)
            assertNotNull("占位 audioUri 必须产生文件段 track", playing.segment?.track)
            assertFalse(playing.degraded)

            // 真实出声：Media3 播放时钟前进（占位音 4000ms）
            withTimeout(STATE_TIMEOUT_MS) { audioPlayer.progressMs.first { (it ?: 0L) > 300L } }

            awaitState { it is PlaybackState.CommandWindow }
            orchestrator.exit()
            assertEquals(PlaybackState.Stopped, orchestrator.state.value)
        }
    }

    @Test
    fun smokeB_audioFailure_fallsBackToTts_sessionContinues(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val bookId = bookWithBoostPlaceholderExample()
            // 篡改为不存在的资源 → Media3 真实 prepare/播放失败（非特殊判断绕过）
            driver.execute(
                null,
                "UPDATE Example SET audioUri = 'res://vb_missing' WHERE sentence LIKE 'The marketing campaign%'",
                0,
            )
            assertTrue(orchestrator.startSession(bookId) is StartResult.Started)

            val playing = awaitState { it is PlaybackState.Playing } as PlaybackState.Playing
            assertEquals(SegmentType.EXAMPLE_AUDIO, playing.segment?.type)
            assertNotNull(playing.segment?.track)

            // §9：音频加载失败 → 该段 TTS 朗读 sentence 兜底 + degraded 广播，会话不中断
            val degraded = withTimeout(STATE_TIMEOUT_MS) {
                orchestrator.state.first { it is PlaybackState.Playing && it.degraded }
            } as PlaybackState.Playing
            assertEquals(SegmentType.EXAMPLE_AUDIO, degraded.segment?.type)

            awaitState { it is PlaybackState.CommandWindow } // TTS 兜底完成 → 窗口照常
            orchestrator.exit()
        }
    }

    @Test
    fun smokeC_pauseResume_fileOffsetContinues(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val bookId = bookWithBoostPlaceholderExample()
            assertTrue(orchestrator.startSession(bookId) is StartResult.Started)
            awaitState { it is PlaybackState.Playing }

            withTimeout(STATE_TIMEOUT_MS) { audioPlayer.progressMs.first { (it ?: 0L) > 1_200L } }
            orchestrator.pause()
            val paused = orchestrator.state.value as PlaybackState.Paused
            assertFalse(paused.atCommandWindow)
            assertTrue("文件段 pause 应记录真实 offsetMs，实际 ${paused.offsetMs}", paused.offsetMs in 1_200..3_999)

            val resumedAt = System.currentTimeMillis()
            orchestrator.resume()
            awaitState { it is PlaybackState.CommandWindow }
            val elapsed = System.currentTimeMillis() - resumedAt
            // 续播剩余 ≈ (4000 - offset) + guard 300 + 窗口开启；整段重播则 ≥ 4000 + 300
            assertTrue("resume 应从 offset 续播而非重播整段，实际耗时 ${elapsed}ms", elapsed < 3_900)
            orchestrator.exit()
        }
    }

    @Test
    fun smokeD_ttsSegment_pauseStopsEngine(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val bookId = bookWithAbandonExample() // 种子无 audioUri → track=null → 纯 TTS 段
            assertTrue(orchestrator.startSession(bookId) is StartResult.Started)

            val playing = awaitState { it is PlaybackState.Playing } as PlaybackState.Playing
            assertEquals(SegmentType.EXAMPLE_AUDIO, playing.segment?.type)
            assertEquals("无 audioUri 的例句应为 TTS 段（非降级）", null, playing.segment?.track)
            assertFalse(playing.degraded)

            withTimeout(STATE_TIMEOUT_MS) { while (!synthesizer.isSpeaking) delay(50) }
            orchestrator.pause()
            val paused = orchestrator.state.value as PlaybackState.Paused
            assertEquals(0L, paused.offsetMs) // TTS 段无 offset 概念（ADR-09：恢复重读本段）
            withTimeout(3_000) { while (synthesizer.isSpeaking) delay(50) } // 引擎层面真停
            orchestrator.exit()
        }
    }

    // —— fixture ——

    /** 建本并保存 boost（verb 释义 1 的例句 0 = 唯一带占位 audioUri 的种子例句，仅勾选该例句）。 */
    private suspend fun bookWithBoostPlaceholderExample(): Long {
        val detail = wordRepository.lookup("boost") ?: error("种子词 boost 缺失")
        val definition = detail.entries.first { it.partOfSpeech == "verb" && it.definitionOrder == 1 }
        val example = detail.examplesByEntryId[definition.definitionEntryId]
            ?.first { it.exampleOrder == 0 }
            ?: error("boost verb/1 例句 0 缺失")
        return bookSaving(detail.word.wordId, DefinitionSelection(definition.definitionEntryId, listOf(example.exampleId)))
    }

    /** 建本并保存 abandon（verb 释义 1 的例句 0，种子无 audioUri → 纯 TTS 段）。 */
    private suspend fun bookWithAbandonExample(): Long {
        val detail = wordRepository.lookup("abandon") ?: error("种子词 abandon 缺失")
        val definition = detail.entries.first { it.partOfSpeech == "verb" && it.definitionOrder == 1 }
        val example = detail.examplesByEntryId[definition.definitionEntryId]
            ?.first { it.exampleOrder == 0 }
            ?: error("abandon verb/1 例句 0 缺失")
        return bookSaving(detail.word.wordId, DefinitionSelection(definition.definitionEntryId, listOf(example.exampleId)))
    }

    private suspend fun bookSaving(wordId: Long, selection: DefinitionSelection): Long {
        val bookId = wordBookRepository.createWordBook("smoke-${System.currentTimeMillis()}")
        wordBookRepository.saveWordToBooks(
            SaveWordRequest(wordId = wordId, wordBookIds = listOf(bookId), selections = listOf(selection))
        )
        return bookId
    }

    private suspend fun awaitState(predicate: (PlaybackState) -> Boolean): PlaybackState =
        withTimeout(STATE_TIMEOUT_MS) { orchestrator.state.first(predicate) }

    private companion object {
        const val STATE_TIMEOUT_MS: Long = 25_000L
    }
}
