package com.vocabularybooster.app.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.db.VocabularyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 统计卡 UI 冒烟（Phase 8.6，TC-UI 统计组；需模拟器）：真实 MainActivity + 真实 Koin 图 +
 * 真实文件库。@Before 清空会话两表（统计只读 SessionWord/LearningSession——其他冒烟
 * 遗留的掌握事件会污染口径；无生产 delete-all query，走 driver 原生 SQL）→
 * 种当日一个掌握事件 → 勋章页统计卡「今日学习」显示 1 词。
 * 种入与断言同取设备时钟，跨午夜竞态概率 ~1/86400，冒烟可接受。
 */
@RunWith(AndroidJUnit4::class)
class StatsCardUiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private val koin get() = GlobalContext.get()

    @Before
    fun setUp() {
        val db = koin.get<VocabularyDatabase>()
        runBlocking {
            db.driver.execute(null, "DELETE FROM SessionWord", 0)
            db.driver.execute(null, "DELETE FROM LearningSession", 0)
        }
    }

    @Test
    fun seededMasteryShowsOnSummaryCard() {
        val db = koin.get<VocabularyDatabase>()
        val now = System.currentTimeMillis()
        runBlocking {
            db.wordBookQueries.insertOriginalWordBook("stats-smoke", null, now, now)
            val bookId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
            db.wordQueries.insertWord("statssmokeword", "statssmokeword", null, null, null, now, now)
            val wordId = db.wordQueries.selectLastInsertRowId().executeAsOne()
            db.learningSessionQueries.insertSession(bookId, "ABANDONED", 10, now, now)
            val sessionId = db.learningSessionQueries.selectLastInsertRowId().executeAsOne()
            db.sessionWordQueries.insertSessionWord(sessionId, wordId, 0, 0, "PENDING")
            db.sessionWordQueries.updateSessionWordStatus("MASTERED", now, sessionId, wordId)
        }

        rule.onNodeWithText("勋章").performClick()
        rule.waitUntil(15_000) {
            runCatching {
                rule.onNode(hasTestTag("stats_today_learned_value")).assertTextEquals("1 词")
            }.isSuccess
        }
    }
}
