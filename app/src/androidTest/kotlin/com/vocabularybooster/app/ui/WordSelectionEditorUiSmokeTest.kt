package com.vocabularybooster.app.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.db.VocabularyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 词条选择编辑 UI 冒烟（Phase 8.5，TC-UI 词条编辑；需模拟器）：真实 MainActivity +
 * 真实 Koin 图——种子两释义 → 本详情「编辑」→ 取消一条释义 → 保存 → DB 选择行减少（FR-17）。
 * 仓储替换/校验/回滚语义由 jvmTest 编辑组锁定，此处只测端到端胶水。
 */
@RunWith(AndroidJUnit4::class)
class WordSelectionEditorUiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private val koin get() = GlobalContext.get()

    private var bookId = 0L
    private var wordId = 0L
    private var verbDefId = 0L
    private var nounDefId = 0L
    private lateinit var bookName: String

    @Before
    fun setUp() {
        runBlocking {
            val words = koin.get<WordRepository>()
            val books = koin.get<WordBookRepository>()
            val detail = words.lookup("boost") ?: error("种子词 boost 缺失")
            wordId = detail.word.wordId
            verbDefId = detail.entries.first { it.partOfSpeech == "verb" }.definitionEntryId
            nounDefId = detail.entries.first { it.partOfSpeech == "noun" }.definitionEntryId
            bookName = "uismoke-edit-${System.currentTimeMillis()}"
            bookId = books.createWordBook(bookName)
            books.saveWordToBooks(
                SaveWordRequest(
                    wordId = wordId,
                    wordBookIds = listOf(bookId),
                    selections = listOf(
                        DefinitionSelection(verbDefId),
                        DefinitionSelection(nounDefId),
                    ),
                ),
            )
        }
    }

    @Test
    fun uncheckOneDefinitionPersistsReducedSelection() {
        val db = koin.get<VocabularyDatabase>()

        rule.onNodeWithText("生词本").performClick()
        rule.onNodeWithText(bookName).performClick()
        rule.onNode(hasTestTag("edit_word_button_$wordId")).performClick()

        // 预填：两条释义都应已勾选 → 取消 noun 释义
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("edit_def_check_$nounDefId")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasTestTag("edit_def_check_$nounDefId")).performClick()
        rule.onNode(hasTestTag("edit_save_button")).performClick()

        rule.waitUntil(10_000) {
            val entryId = db.wordBookEntryQueries.selectEntryByWord(bookId, wordId)
                .executeAsOneOrNull()?.wordBookEntryId ?: -1L
            db.wordBookEntryDefinitionQueries.selectEntryDefinitions(entryId)
                .executeAsList().map { it.definitionEntryId } == listOf(verbDefId)
        }
    }
}
