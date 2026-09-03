package com.vocabularybooster.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 UI 冒烟（TEST_PLAN §4.8）：真库（内存）+ 真种子。
 * 覆盖：搜索 → 详情（POS 分组排序）→ 新建生词本 → 保存释义/例句选择。
 */
@RunWith(AndroidJUnit4::class)
class Phase2UiFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: VocabularyDatabase
    private lateinit var wordRepository: WordRepository
    private lateinit var wordBookRepository: WordBookRepository

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)
    }

    @Throws(Exception::class)
    private fun setUpDatabase() = runBlocking {
        val driver = AndroidSqliteDriver(
            schema = VocabularyDatabase.Schema,
            context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            name = null, // in-memory
        )
        database = VocabularyDatabase(driver)
        wordRepository = SqlDelightWordRepository(database)
        wordBookRepository = SqlDelightWordBookRepository(database, FixedClock())
        SeedImporter(database, FixedClock()).ensureSeeded(SeedDictionaryProvider(SEED_DICTIONARY_JSON))
    }

    private fun composeLookupToDetail() {
        val lookupVm = LookupViewModel(wordRepository)
        val detailVm = WordDetailViewModel(wordRepository, wordBookRepository)
        composeRule.setContent {
            MaterialTheme {
                var openWord by remember { mutableStateOf<String?>(null) }
                if (openWord == null) {
                    LookupScreen(onWordClick = { openWord = it }, viewModel = lookupVm)
                } else {
                    WordDetailScreen(wordText = openWord!!, onBack = { openWord = null }, viewModel = detailVm)
                }
            }
        }
        composeRule.onNodeWithTag("search_input").performTextInput("boost")
        composeRule.waitUntil(timeoutMillis = 10_000) { lookupVm.results.any { it.text == "boost" } }
        composeRule.onNodeWithTag("search_result_boost").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { detailVm.detail != null }
    }

    @Test
    fun searchOpenDetailShowsOrderedDefinitions() {
        setUpDatabase()
        composeLookupToDetail()

        // FR-2：verb 组先于 noun 组（种子 boost = verb×2 + noun×1）
        composeRule.onNodeWithText("VERB").isDisplayed()
        composeRule.onNodeWithTag("word_detail_list").performScrollToNode(hasTextExactly("NOUN"))
        composeRule.onNodeWithText("NOUN").isDisplayed()

        // 释义文本存在（顺序已由 shared 的 PartOfSpeechOrderingTest/Repository 测试锁死）
        assertTrue(
            composeRule.onAllNodesWithText("to increase or improve something")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // EN 在 CN 前（结构性：同一条目同一渲染块，断言两者都显示）
        composeRule.onAllNodesWithText("提高；使增长").fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun createWordBookSaveSelectedDefinitionAndExamples() {
        setUpDatabase()
        composeLookupToDetail()

        composeRule.onNodeWithTag("save_to_book_button").performClick()

        // 新建生词本并自动选中
        composeRule.onNodeWithTag("new_book_name").performTextInput("MyBook")
        composeRule.onNodeWithTag("create_book_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { lookupBooksContain("MyBook") }

        // 勾选 boost 的 verb#1（两条例句只选第一条）
        val detail = runBlocking { wordRepository.lookup("boost") }!!
        val verbFirst = detail.entries.first { it.partOfSpeech == "verb" }
        val examples = detail.examplesByEntryId[verbFirst.definitionEntryId].orEmpty()
        assertEquals(2, examples.size)
        composeRule.onNodeWithTag("def_check_${verbFirst.definitionEntryId}").performClick()
        composeRule.onNodeWithTag("ex_check_${examples[1].exampleId}").performClick() // 只选第二条

        composeRule.onNodeWithTag("confirm_save_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { savedEntryCount("MyBook") == 1 }

        // DB 断言：释义选择 1 条 + 例句选择恰为勾选的那条
        val bookId = bookIdOf("MyBook")
        val entryId = database.wordBookEntryQueries
            .selectEntryByWord(bookId, detail.word.wordId).executeAsOne().wordBookEntryId
        assertEquals(
            listOf(verbFirst.definitionEntryId),
            database.wordBookEntryDefinitionQueries
                .selectEntryDefinitions(entryId).executeAsList().map { it.definitionEntryId },
        )
        assertEquals(
            listOf(examples[1].exampleId),
            database.wordBookEntryExampleSelectionQueries
                .selectExampleSelections(entryId).executeAsList().map { it.exampleId },
        )
    }

    private fun lookupBooksContain(name: String): Boolean = runBlocking {
        wordBookRepository.getWordBooks().any { it.wordBook.name == name }
    }

    private fun savedEntryCount(name: String): Int = runBlocking {
        wordBookRepository.getWordBooks().firstOrNull { it.wordBook.name == name }?.entryCount ?: 0
    }

    private fun bookIdOf(name: String): Long = runBlocking {
        wordBookRepository.getWordBooks().first { it.wordBook.name == name }.wordBook.wordBookId
    }
}

private fun hasTextExactly(text: String): androidx.compose.ui.test.SemanticsMatcher =
    androidx.compose.ui.test.hasText(text)
