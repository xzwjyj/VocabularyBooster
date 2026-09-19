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
 * 真实文件库。@Before 清空 SessionWord 掌握事件（今日学习数唯一污染源——前序冒烟会
 * 掌握种子词；不清 LearningSession：DERIVED 本 sourceSessionId 外键无级联会拦删，
 * 且会话残留不影响本断言）→ 种当日一个掌握事件 → 勋章页统计卡「今日学习」显示 1 词。
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
            db.sessionWordQueries.deleteAllSessionWords()
        }
    }

    @Test
    fun seededMasteryShowsOnSummaryCard() {
        val db = koin.get<VocabularyDatabase>()
        val now = System.currentTimeMillis()
        runBlocking {
            db.wordBookQueries.insertOriginalWordBook("stats-smoke-$now", null, now, now)
            val bookId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
            // 幂等：本词可能已由上次运行种入（am instrument 重跑不卸载不清库）
            val wordId = db.wordQueries.selectByNormalizedText("statssmokeword").executeAsOneOrNull()?.wordId
                ?: run {
                    db.wordQueries.insertWord("statssmokeword", "statssmokeword", null, null, null, now, now)
                    db.wordQueries.selectLastInsertRowId().executeAsOne()
                }
            db.learningSessionQueries.insertSession(bookId, "ABANDONED", 10, now, now)
            val sessionId = db.learningSessionQueries.selectLastInsertRowId().executeAsOne()
            db.sessionWordQueries.insertSessionWord(sessionId, wordId, 0, 0, "PENDING")
            db.sessionWordQueries.updateSessionWordStatus("MASTERED", now, sessionId, wordId)
        }

        rule.onNodeWithText("勋章").performClick()
        try {
            rule.waitUntil(15_000) {
                runCatching {
                    // 统计卡整体 clickable（mergeDescendants）——值文本断言必须走 unmerged 树
                    rule.onNode(hasTestTag("stats_today_learned_value"), useUnmergedTree = true)
                        .assertTextEquals("1 词")
                }.isSuccess
            }
        } catch (e: Throwable) {
            error("统计卡未显示今日学习 1 词：${e.message}")
        }
    }
}
