package com.vocabularybooster.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 正式学习会话 UI 冒烟（Phase 4 Step 4，需模拟器）：
 * 真实用户流程 = MainActivity（真实 Koin 图 / 文件库 / Media3 / TTS）→
 * 生词本 → 本详情「开始学习」→ LearningSessionScreen → 编排器 → 播放端口。
 * 无 fake playback / 无 emulator 专用逻辑 / 无 sleep 伪完成；状态等待全部超时封顶轮询。
 *
 * 覆盖矩阵：A 打开屏 / B 占位音频 Media3 全程播放到窗口 / C Pause→Resume /
 * D 缺音频→Media3 失败→TTS 兜底横幅 / E 窗口倒计时可见推进 / F Next 推进 /
 * G Exit 确认离开 / H Completed 确定性 fixture 渲染 /
 * I 窗口内「会了」按钮 → 同路径掌握推进（Phase 5 Step 1，确定性）/ J 权限拒绝 → 窗口降级提示 +
 * 未授权麦克风横幅（Phase 5 Step 1，拒绝态自适应——运行时 pm revoke 会杀运行中 app 进程，全套件禁用）。
 * （真实语音「会了」识别需人对麦克风说话 = 手动矩阵，不在自动化内伪造；
 *  BOOK_DELETED 恢复流程见 BookDeletedRecoveryUiSmokeTest——同进程单例无法模拟进程重建。）
 */
@RunWith(AndroidJUnit4::class)
class LearningSessionUiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val koin get() = GlobalContext.get()

    @Before
    fun setUp() {
        runBlocking {
            // 隔离：上一用例（含失败）遗留的会话先走既有 exit（终态幂等），再兜底清扫孤儿 ACTIVE 会话。
            // exit 的 stopPorts 触达 Media3/TTS（两端口 stop 均主线程限定）——必须在 Main.immediate 执行；
            // 若在本（instrumentation）线程直接调用，端口的主线程断言会把 exit 中途打断且异常被吞，
            // 单例滞留半退出态（state=Playing 但驱动已死），下一用例 start 的传输态守卫即被 stale 状态拦截。
            withContext(Dispatchers.Main.immediate) {
                val playback = koin.get<PlaybackOrchestrator>()
                var attempts = 0
                while (attempts < 3 && playback.state.value.isTransportActive) {
                    runCatching { playback.exit() }
                    attempts++
                }
            }
            val sessions = koin.get<LearningSessionRepository>()
            sessions.getActiveSession()?.let {
                sessions.terminateSessionIfActive(it.sessionId, SessionStatus.ABANDONED)
            }
            koin.get<PlaybackPositionRepository>().clear()
            // 种子确定性：应用首启异步导入的同步兜底（ensureSeeded 幂等）
            val words = koin.get<WordRepository>()
            withTimeout(20_000) { while (words.lookup("boost") == null) delay(200) }
            writeSetting("settings.commandWindowMs", "1200")
            writeSetting("settings.playbackToggles", EXAMPLE_ONLY_TOGGLES)
        }
    }

    // —— A. 打开学习屏：首词可见 ——

    @Test
    fun smokeA_openLearningScreen_showsFirstWord() {
        val bookName = createBook("a", listOf("boost"))
        openLearningScreen(bookName)

        try {
            awaitTag("learning_word", 25_000)
        } catch (e: Throwable) {
            diag("A-learning_word")
        }
        rule.onNode(hasTestTag("learning_word")).assertTextEquals("boost")
        awaitTag("learning_group", 5_000)

        exitLearningViaUi()
    }

    // —— B. 真实占位音频：Media3 播完全段 → 窗口开启 ——

    @Test
    fun smokeB_fileAudio_playsThroughMedia3_reachesCommandWindow() {
        val bookName = createBook("b", listOf("boost"))
        openLearningScreen(bookName)

        awaitTextSubstr("正在播放", 25_000)
        awaitTag("learning_countdown", 25_000) // 占位音 4000ms + guard 300ms 后窗口开启 = 真实播完

        exitLearningViaUi()
    }

    // —— C. Pause → Paused → Resume 回 Playing ——

    @Test
    fun smokeC_pause_showsPaused_resumeReturnsPlaying() {
        val bookName = createBook("c", listOf("boost"))
        openLearningScreen(bookName)
        awaitTextSubstr("正在播放", 25_000)

        clickTag("btn_pause")
        try {
            awaitTextSubstr("已暂停", 10_000)
        } catch (e: Throwable) {
            diag("C-paused")
        }

        clickTag("btn_resume")
        awaitTextSubstr("正在播放", 25_000) // TTS/文件段按 ADR-09 重读当前段

        exitLearningViaUi()
    }

    // —— D. 缺音频 → Media3 真失败 → TTS 兜底（degraded 横幅）——

    @Test
    fun smokeD_missingAudio_media3Fails_ttsFallbackBanner() {
        val original = corruptBoostExampleAudio()
        try {
            val bookName = createBook("d", listOf("boost"))
            openLearningScreen(bookName)

            try {
                awaitTag("learning_degraded", 25_000) // §9：音频失败 → TTS 朗读 sentence + 降级广播
            } catch (e: Throwable) {
                diag("D-degraded")
            }
        } finally {
            restoreBoostExampleAudio(original)
        }

        exitLearningViaUi()
    }

    // —— E. CommandWindow 倒计时可见推进 ——

    @Test
    fun smokeE_commandWindow_countdownProgresses() {
        writeSetting("settings.commandWindowMs", "3000") // 放宽到 3s，保证两次采样可分
        val bookName = createBook("e", listOf("boost", "abandon"))
        openLearningScreen(bookName)

        try {
            awaitTag("learning_countdown", 25_000)
        } catch (e: Throwable) {
            diag("E-countdown")
        }
        val first = textOfTag("learning_countdown")
        rule.waitUntil(25_000) { textOfTag("learning_countdown") !in listOf(null, first) } // 剩余时长递减

        exitLearningViaUi()
    }

    // —— F. Next 按编排器语义推进到第二词 ——

    @Test
    fun smokeF_next_advancesToSecondWord() {
        val bookName = createBook("f", listOf("boost", "abandon"))
        openLearningScreen(bookName)
        awaitTextSubstr("正在播放", 25_000)
        assertEquals("boost", textOfTag("learning_word"))

        clickTag("btn_next")
        try {
            rule.waitUntil(25_000) { textOfTag("learning_word") == "abandon" } // UI→VM→编排器→引擎 advance
        } catch (e: Throwable) {
            diag("F-abandon")
        }

        exitLearningViaUi()
    }

    // —— G. Exit 二次确认后正确离开 ——

    @Test
    fun smokeG_exitConfirmed_returnsToBookDetail() {
        val bookName = createBook("g", listOf("boost"))
        openLearningScreen(bookName)
        try {
            awaitTextSubstr("正在播放", 25_000)
        } catch (e: Throwable) {
            diag("G-playing")
        }

        clickTag("btn_exit")
        clickTag("btn_confirm_exit")
        awaitTag("start_learning_button", 25_000) // 回到本详情（Stopped → 导航离开）
    }

    // —— H. Completed 渲染（确定性 fixture：库中全部词已掌握 → 窗口超时 → 引擎判完成）——

    @Test
    fun smokeH_completed_rendersWithDeterministicFixture() {
        val bookName = createBook("h", listOf("boost"))
        openLearningScreen(bookName)
        awaitTextSubstr("正在播放", 25_000)

        runBlocking {
            val repo = koin.get<LearningSessionRepository>()
            val session = repo.getActiveSession() ?: error("学习屏起播后应有 ACTIVE 会话")
            val wordId = koin.get<WordRepository>().lookup("boost")?.word?.wordId ?: error("种子词 boost 缺失")
            assertTrue("预置掌握应写入成功", repo.markSessionWordMastered(session.sessionId, wordId, Clock.System.now()))
        }

        awaitTextSubstr("学习完成", 25_000) // 窗口超时 → advance → BookComplete（完成权威 = 引擎）
        clickTag("btn_back_to_books")
        awaitTag("start_learning_button", 25_000)
    }

    // —— I.（Phase 5 Step 1）窗口内「会了」按钮 → markMastered(BUTTON) → 推进第二词 ——
    // 确定性入口（按钮与语音同一执行路径；真实语音识别 = 手动矩阵）

    @Test
    fun smokeI_masteredButton_advancesToSecondWord() {
        writeSetting("settings.commandWindowMs", "5000") // 放宽窗口，保证 UI 自动化点击余量
        val bookName = createBook("i", listOf("boost", "abandon"))
        openLearningScreen(bookName)
        awaitTextSubstr("正在播放", 25_000)
        assertEquals("boost", textOfTag("learning_word"))

        awaitTag("learning_countdown", 25_000) // 占位音播完 + guard → 窗口开启（btn_mastered 同帧渲染）
        clickTag("btn_mastered")
        try {
            rule.waitUntil(25_000) { textOfTag("learning_word") == "abandon" } // 按钮掌握 → 引擎 advance
        } catch (e: Throwable) {
            diag("I-abandon")
        }

        exitLearningViaUi()
    }

    // —— J.（Phase 5 Step 1）权限拒绝 → 窗口降级提示 + 未授权麦克风横幅（拒绝态自适应）——

    @Test
    fun smokeJ_windowDegradesWhenPermissionDenied_andShowsMicBanner() {
        // D5 端到端呈现：拒绝 + 服务在场 → listenOnce 硬失败 → 整窗降级（「语音命令不可用」）；
        // 服务缺席 → 前置门即降级——两分支诚实呈现同文。拒绝态依赖套件顺序（前序用例可能已
        // pm grant；运行时 revoke 会杀运行中 app 进程，禁用）→ 自适应执行/跳过（同 TTS 引擎缺席法）。
        val denied = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        assumeTrue("RECORD_AUDIO 已被前序用例授予：未授权呈现转手动矩阵", denied)
        writeSetting("settings.commandWindowMs", "5000") // 给降级广播留余量
        val bookName = createBook("j", listOf("boost"))
        openLearningScreen(bookName)

        awaitTag("learning_countdown", 25_000) // 窗口开启
        awaitTag("learning_listening", 5_000) // 语音形态指示渲染（不伪造录音状态）
        awaitTextSubstr("语音命令不可用", 5_000) // 拒绝/服务缺席 → 降级提示（诚实呈现）
        awaitTag("mic_banner", 5_000) // 未授权 → 非阻断授权条（「会了」按钮不受影响）

        exitLearningViaUi()
    }

    // —— 驱动与断言助手（waitUntil 轮询，不依赖 compose idle——窗口倒计时的 100ms tick 会持续占用主线程）——

    /**
     * 排障转储（Step 4 冒烟定位期）：等待超时时区分「编排器状态未到」与「状态已到但 UI 未渲染」——
     * 同时输出编排器当前状态与屏幕各候选内容，直接以失败消息形式出现在测试报告里。
     */
    private fun diag(where: String): Nothing {
        val state = runBlocking { koin.get<PlaybackOrchestrator>().state.value }
        val loading = rule.onAllNodesWithText("正在准备学习").fetchSemanticsNodes().isNotEmpty()
        val playing = rule.onAllNodesWithText("正在播放", substring = true).fetchSemanticsNodes().isNotEmpty()
        val conflict = rule.onAllNodesWithText("已有进行中的", substring = true).fetchSemanticsNodes().isNotEmpty()
        val wordOnScreen = textOfTag("learning_word")
        val backAtDetail = rule.onAllNodes(hasTestTag("start_learning_button")).fetchSemanticsNodes().isNotEmpty()
        error(
            "DIAG[$where] state=${state::class.simpleName} word=$wordOnScreen " +
                "loading=$loading playing=$playing conflict=$conflict backAtDetail=$backAtDetail",
        )
    }

    private fun awaitTag(tag: String, timeoutMs: Long) {
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitTextSubstr(text: String, timeoutMs: Long) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickTag(tag: String) {
        awaitTag(tag, 10_000)
        rule.onNode(hasTestTag(tag)).performClick()
    }

    private fun textOfTag(tag: String): String? =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
            ?.config?.get(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** 生词本 Tab → 目标本 → 开始学习。（Tab 标签与页标题同文——只有 Tab 可点击。） */
    private fun openLearningScreen(bookName: String) {
        rule.waitUntil(25_000) { rule.onAllNodesWithText("生词本").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("生词本").filterToOne(hasClickAction()).performClick()
        rule.waitUntil(25_000) { rule.onAllNodesWithText(bookName).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(bookName).performClick()
        clickTag("start_learning_button")
    }

    private fun exitLearningViaUi() {
        clickTag("btn_exit")
        clickTag("btn_confirm_exit")
        awaitTag("start_learning_button", 25_000)
    }

    // —— fixture（真实仓储 API，直写 AppSetting 仅设置项——设置页属 Phase 8）——

    private fun writeSetting(key: String, value: String) {
        koin.get<VocabularyDatabase>().appSettingQueries.upsertSetting(key, value)
    }

    /** 建本 + 保存词（每词勾选 verb 释义 1 的例句 0——boost 例句带占位 audioUri，abandon 纯 TTS）。 */
    private fun createBook(label: String, words: List<String>): String {
        val name = "uismoke-$label-${System.currentTimeMillis()}"
        runBlocking {
            val books = koin.get<WordBookRepository>()
            val wordRepo = koin.get<WordRepository>()
            val bookId = books.createWordBook(name)
            for (text in words) {
                val detail = wordRepo.lookup(text) ?: error("种子词 $text 缺失")
                val definition = detail.entries.first { it.partOfSpeech == "verb" && it.definitionOrder == 1 }
                val example = detail.examplesByEntryId[definition.definitionEntryId]
                    ?.first { it.exampleOrder == 0 }
                    ?: error("$text verb/1 例句 0 缺失")
                books.saveWordToBooks(
                    SaveWordRequest(
                        wordId = detail.word.wordId,
                        wordBookIds = listOf(bookId),
                        selections = listOf(DefinitionSelection(definition.definitionEntryId, listOf(example.exampleId))),
                    ),
                )
            }
        }
        return name
    }

    /** 篡改 boost 占位例句 audioUri → Media3 真实 prepare/播放失败（§9 TTS 兜底被测路径）；返回原值供还原。 */
    private fun corruptBoostExampleAudio(): String? {
        val db = openAppDb()
        try {
            val original = db.rawQuery(
                "SELECT audioUri FROM Example WHERE sentence LIKE 'The marketing campaign%'",
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) error("占位例句缺失（种子未导入？）")
                if (cursor.isNull(0)) null else cursor.getString(0)
            }
            db.execSQL(
                "UPDATE Example SET audioUri = 'res://vb_missing' WHERE sentence LIKE 'The marketing campaign%'",
            )
            return original
        } finally {
            db.close()
        }
    }

    private fun restoreBoostExampleAudio(original: String?) {
        val db = openAppDb()
        try {
            db.execSQL(
                "UPDATE Example SET audioUri = ? WHERE sentence LIKE 'The marketing campaign%'",
                arrayOf(original),
            )
        } finally {
            db.close()
        }
    }

    /** 应用文件库的并行连接（仅 fixture 数据手术；正常数据流一律走仓储）。 */
    private fun openAppDb(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("vocabulary.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )

    private companion object {
        /** 仅保留例句段（单段定位被测路径；与 Phase 4 Step 3 冒烟同形）。 */
        const val EXAMPLE_ONLY_TOGGLES: String =
            """{"pronunciation":false,"spelling":false,"meaningEn":false,"meaningCn":false,"example":true,"exampleCn":false}"""
    }
}

/** 与编排器 §6 同判：传输态存活时 start 守卫会拦截（隔离循环须退到终态）。 */
private val PlaybackState.isTransportActive: Boolean
    get() = this is PlaybackState.Playing || this is PlaybackState.Paused || this is PlaybackState.CommandWindow
