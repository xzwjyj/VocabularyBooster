package com.vocabularybooster.app.ui

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
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
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.platform.Media3AudioPlayer
import com.vocabularybooster.platform.TtsSpeechSynthesizer
import com.vocabularybooster.speech.CommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BOOK_DELETED 恢复 UI 冒烟（Phase 4 Step 4，需模拟器；TC-AE-15 UI 侧）：
 * 进程重建场景 = 全新 PlaybackOrchestrator（Idle）+ 真实引擎/真实 in-memory 库 + 真实端口 actual，
 * 库中遗留 ACTIVE 会话且其书已被删（悬挂态经 PRAGMA foreign_keys=OFF 构造——生产 UI 不可达：
 * 学习屏全屏覆盖 + FK RESTRICT；与 Phase 3 jvmTest 同法）。
 *
 * 流程：进入学习（书 Y 正常）→ startSession 撞 ACTIVE_SESSION_EXISTS（旧会话在书 X）→
 * 冲突弹窗「恢复」→ resumeSession → BOOK_DELETED（引擎 TC-LE-10 完整性检查）→
 * 提示弹窗「生词本已删除」→ 确定 → 返回上一层。
 * 真实 ComponentActivity + 真实 LearningSessionScreen 渲染；无 fake、无 sleep 伪完成。
 * 同进程应用级单例无法模拟进程重建（其内存态仍 transport-active 会拦截 start），
 * 故本用例用独立栈等价模拟——所有 backend 均为 actual 实现。
 */
@RunWith(AndroidJUnit4::class)
class BookDeletedRecoveryUiSmokeTest {

    @get:Rule
    val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock: Clock = Clock.System

    private lateinit var driver: AndroidSqliteDriver
    private lateinit var database: VocabularyDatabase
    private lateinit var books: SqlDelightWordBookRepository
    private lateinit var words: SqlDelightWordRepository
    private lateinit var engine: DefaultLearningEngine
    private lateinit var orchestrator: PlaybackOrchestrator
    private lateinit var audioPlayer: Media3AudioPlayer
    private lateinit var synthesizer: TtsSpeechSynthesizer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                driver = AndroidSqliteDriver(VocabularyDatabase.Schema, context) // in-memory
                database = VocabularyDatabase(driver)
                val settings = SqlDelightLearningSettingsRepository(database)
                val sessions = SqlDelightLearningSessionRepository(database, clock)
                books = SqlDelightWordBookRepository(database, clock)
                words = SqlDelightWordRepository(database)
                SeedImporter(database, clock).ensureSeeded(SeedDictionaryProvider(SEED_DICTIONARY_JSON))
                audioPlayer = Media3AudioPlayer(context)
                synthesizer = TtsSpeechSynthesizer(context)
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                engine = DefaultLearningEngine(
                    sessionRepository = sessions,
                    settingsRepository = settings,
                    masteryMarker = MasteryMarker(repository = sessions, clock = clock),
                    wordBookDeriver = WordBookDeriver(wordBookRepository = books, clock = clock),
                )
                orchestrator = PlaybackOrchestrator(
                    engine = engine,
                    contentRepository = SqlDelightPlaybackContentRepository(database),
                    positionRepository = SqlDelightPlaybackPositionRepository(database),
                    settingsRepository = settings,
                    audioPlayer = audioPlayer,
                    synthesizer = synthesizer,
                    // 真实识别 actual（未授予 RECORD_AUDIO → 窗口降级纯倒计时，本用例语义不受影响）
                    recognizer = AndroidSpeechCommandRecognizer(context),
                    commandParser = CommandParser(),
                    eventBus = DefaultDomainEventBus(),
                    scope = scope,
                )
            }
        }
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        if (::audioPlayer.isInitialized || ::synthesizer.isInitialized) {
            runBlocking {
                withContext(Dispatchers.Main.immediate) {
                    if (::audioPlayer.isInitialized) audioPlayer.release()
                    if (::synthesizer.isInitialized) synthesizer.release()
                }
            }
        }
        if (::driver.isInitialized) driver.close()
    }

    @Test
    fun danglingActiveSession_bookDeleted_showsNoticeAndReturns() {
        val targetBookId: Long
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                // 书 X：留下 ACTIVE 会话（真实引擎 startSession，不起播不退出——进程被杀现场）
                val activeBookId = createBook("deleted", "boost")
                assertTrue("旧会话应创建成功", engine.startSession(activeBookId) is StartResult.Started)

                // 悬挂态：FK RESTRICT 下生产删不掉带 ACTIVE 会话的书——测试直构（PRAGMA OFF + DELETE）
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                driver.execute(null, "DELETE FROM WordBook WHERE wordBookId = $activeBookId", 0)

                // 书 Y：本次要学的正常书
                targetBookId = createBook("target", "abandon")
            }
        }

        val exited = AtomicBoolean(false)
        val viewModel = runBlocking {
            withContext(Dispatchers.Main.immediate) { LearningSessionViewModel(orchestrator) } // 全新编排器 = 进程重建
        }
        rule.setContent {
            LearningSessionScreen(bookId = targetBookId, onExit = { exited.set(true) }, viewModel = viewModel)
        }

        // startSession(书 Y) → ACTIVE_SESSION_EXISTS(书 X 的会话) → 冲突弹窗
        rule.waitUntil(25_000) { rule.onAllNodes(hasTestTag("btn_conflict_resume")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag("btn_conflict_resume")).performClick()

        // resumeSession(旧会话) → BOOK_DELETED（引擎完整性检查）→ 提示弹窗
        rule.waitUntil(25_000) {
            rule.onAllNodesWithText("生词本已删除", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasTestTag("btn_notice_confirm")).performClick()

        rule.waitUntil(10_000) { exited.get() } // 确定 → 返回上一层（onExit）
    }

    // —— fixture ——

    /** 建本 + 保存词（verb 释义 1 例句 0；真实仓储 API；主线程限定上下文内调用）。 */
    private suspend fun createBook(label: String, wordText: String): Long {
        val detail = words.lookup(wordText) ?: error("种子词 $wordText 缺失")
        val definition = detail.entries.first { it.partOfSpeech == "verb" && it.definitionOrder == 1 }
        val example = detail.examplesByEntryId[definition.definitionEntryId]
            ?.first { it.exampleOrder == 0 }
            ?: error("$wordText verb/1 例句 0 缺失")
        val bookId = books.createWordBook("uismoke-i-$label-${System.currentTimeMillis()}")
        books.saveWordToBooks(
            SaveWordRequest(
                wordId = detail.word.wordId,
                wordBookIds = listOf(bookId),
                selections = listOf(DefinitionSelection(definition.definitionEntryId, listOf(example.exampleId))),
            ),
        )
        return bookId
    }
}
