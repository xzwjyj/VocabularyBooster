package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.WordBookSelectionSnapshot
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.domain.repository.WordBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6：生词本删除拦截 → 中文文案（FR-4/FR-13 UI 侧；shared 守卫语义已由
 * WordBookRepositoryTest.deleteGuardsRejectMedalAndDerivedParent 锁死，此处只测 VM 文案分支）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WordBooksViewModelTest {

    /** 最小 Fake：列表流空态；[deleteError] 注入删除守卫异常。 */
    private class FakeRepo(private val deleteError: Exception? = null) : WordBookRepository {
        override fun observeWordBooks(): Flow<List<WordBookSummary>> = MutableStateFlow(emptyList())
        override suspend fun getWordBooks(): List<WordBookSummary> = emptyList()
        override suspend fun createWordBook(name: String): Long = 1L
        override suspend fun renameWordBook(wordBookId: Long, newName: String): Unit = Unit
        override suspend fun deleteWordBook(wordBookId: Long) {
            deleteError?.let { throw it }
        }
        override suspend fun getWordBookWords(wordBookId: Long): List<WordBookWord> = emptyList()
        override suspend fun removeWordFromWordBook(wordBookId: Long, wordId: Long): Unit = Unit
        override suspend fun saveWordToBooks(request: SaveWordRequest): Unit = Unit
        override suspend fun getWordBookName(wordBookId: Long): String? = null
        override suspend fun countBooksWithName(name: String): Int = 0
        override suspend fun deriveWordBook(
            parentWordBookId: Long,
            sourceSessionId: Long,
            name: String,
            createdAt: Instant,
        ): Long? = null

        // Phase 8.5 编辑两方法：本测试不驱动——良性桩
        override suspend fun getWordSelections(
            wordBookId: Long,
            wordId: Long,
        ): WordBookSelectionSnapshot? = null

        override suspend fun updateWordSelections(
            wordBookId: Long,
            wordId: Long,
            selections: List<DefinitionSelection>,
        ): Unit = Unit
    }

    @Test
    fun deleteBlockedByMedalShowsChineseMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = WordBooksViewModel(
                FakeRepo(WordBookDeletionException(1L, WordBookDeletionException.Reason.BOOK_HAS_COMPLETION_MEDAL)),
            )
            vm.deleteBook(1L)
            advanceUntilIdle()
            assertEquals("该生词本已获得完成勋章，不能删除", vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deleteBlockedByDerivedChildrenShowsChineseMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = WordBooksViewModel(
                FakeRepo(WordBookDeletionException(1L, WordBookDeletionException.Reason.HAS_DERIVED_CHILDREN)),
            )
            vm.deleteBook(1L)
            advanceUntilIdle()
            assertEquals("该生词本存在派生生词本，请先删除派生本", vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deleteSuccessClearsMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = WordBooksViewModel(FakeRepo(deleteError = null))
            vm.deleteBook(1L)
            advanceUntilIdle()
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
