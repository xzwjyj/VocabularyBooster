package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.DefinitionEntry
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBookSelectionSnapshot
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词条选择编辑 VM（FR-17，Phase 8.5）：预填投影 / 勾选增删 → 端口转发 /
 * ≥1 释义前置拦截 / 成功失败文案。仓储替换语义的权威测试在 jvmTest
 * WordBookRepositoryTest 编辑组（真实 JDBC）；此处只测 VM 映射（铁律：VM 无业务规则）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WordSelectionEditorViewModelTest {

    // —— 固定词条：boost，verb 释义 11（例句 111/112）+ noun 释义 12（例句 121） ——
    private val word = Word(wordId = 1L, text = "boost", normalizedText = "boost")
    private val defVerb = DefinitionEntry(11L, 1L, "verb", 0, 1, "to push up", "增强")
    private val defNoun = DefinitionEntry(12L, 1L, "noun", 1, 1, "a boost", "一次提升")
    private fun example(id: Long, defId: Long) = Example(id, defId, "sentence $id", "例句 $id", ExampleSourceType.TTS)
    private val detail = WordDetail(
        word = word,
        entries = listOf(defVerb, defNoun),
        examplesByEntryId = mapOf(
            11L to listOf(example(111L, 11L), example(112L, 11L)),
            12L to listOf(example(121L, 12L)),
        ),
    )

    private class FakeWordRepo(var detail: WordDetail?) : WordRepository {
        override suspend fun lookup(text: String): WordDetail? = detail
        override suspend fun search(query: String, limit: Int): List<Word> = emptyList()
    }

    /** 编辑路径专用最小 Fake：其余端口方法良性桩（手写 Fake，无 mock 框架）。 */
    private class FakeEditorBookRepo(
        var snapshot: WordBookSelectionSnapshot? = null,
        var failWrites: Boolean = false,
    ) : WordBookRepository {
        val updateCalls = mutableListOf<Triple<Long, Long, List<DefinitionSelection>>>()
        var lastSavedSelections: List<DefinitionSelection> = emptyList()
            private set

        override suspend fun getWordSelections(wordBookId: Long, wordId: Long): WordBookSelectionSnapshot? = snapshot
        override suspend fun updateWordSelections(
            wordBookId: Long,
            wordId: Long,
            selections: List<DefinitionSelection>,
        ) {
            if (failWrites) throw RepositoryValidationException("测试注入的写入失败")
            updateCalls += Triple(wordBookId, wordId, selections)
            lastSavedSelections = selections
            snapshot = WordBookSelectionSnapshot(snapshot?.wordBookEntryId ?: 1L, selections)
        }

        override fun observeWordBooks(): Flow<List<WordBookSummary>> = MutableStateFlow(emptyList())
        override suspend fun getWordBooks(): List<WordBookSummary> = emptyList()
        override suspend fun createWordBook(name: String): Long = 1L
        override suspend fun renameWordBook(wordBookId: Long, newName: String): Unit = Unit
        override suspend fun deleteWordBook(wordBookId: Long): Unit = Unit
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
    }

    @Test
    fun loadPrefillsCurrentSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeEditorBookRepo(
                snapshot = WordBookSelectionSnapshot(
                    wordBookEntryId = 7L,
                    selections = listOf(
                        DefinitionSelection(11L, listOf(111L)),
                        DefinitionSelection(12L, listOf(121L)),
                    ),
                ),
            )
            val vm = WordSelectionEditorViewModel(FakeWordRepo(detail), repo)
            vm.load(targetBookId = 5L, targetWordId = 1L, wordText = "boost")
            advanceUntilIdle()

            assertTrue(vm.loaded)
            assertEquals(setOf(11L, 12L), vm.selectedDefinitions)
            assertEquals(mapOf(11L to setOf(111L), 12L to setOf(121L)), vm.selectedExamples)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun togglingThenSaveReplacesSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeEditorBookRepo(
                snapshot = WordBookSelectionSnapshot(
                    7L,
                    listOf(DefinitionSelection(11L, listOf(111L)), DefinitionSelection(12L, listOf(121L))),
                ),
            )
            val vm = WordSelectionEditorViewModel(FakeWordRepo(detail), repo)
            vm.load(5L, 1L, "boost")
            advanceUntilIdle()

            // 增删并存：noun 取消、verb 勾第二例句
            vm.toggleDefinition(12L)
            vm.toggleExample(11L, 112L)
            vm.save()
            advanceUntilIdle()

            assertEquals(listOf(Triple(5L, 1L, listOf(DefinitionSelection(11L, listOf(111L, 112L))))), repo.updateCalls)
            assertEquals("已保存", vm.message)
            assertTrue(vm.saved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun emptySelectionIsBlockedBeforePort() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeEditorBookRepo(snapshot = WordBookSelectionSnapshot(7L, listOf(DefinitionSelection(11L))))
            val vm = WordSelectionEditorViewModel(FakeWordRepo(detail), repo)
            vm.load(5L, 1L, "boost")
            advanceUntilIdle()

            vm.toggleDefinition(11L)
            vm.save()
            advanceUntilIdle()

            assertEquals("至少保留一条释义（不需要该词请用「移除」）", vm.message)
            assertTrue(repo.updateCalls.isEmpty()) // 前置拦截，端口未被调用
            assertFalse(vm.saved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun saveFailureShowsChineseMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeEditorBookRepo(
                snapshot = WordBookSelectionSnapshot(7L, listOf(DefinitionSelection(11L))),
                failWrites = true,
            )
            val vm = WordSelectionEditorViewModel(FakeWordRepo(detail), repo)
            vm.load(5L, 1L, "boost")
            advanceUntilIdle()

            vm.save()
            advanceUntilIdle()

            assertEquals("保存失败：测试注入的写入失败", vm.message)
            assertFalse(vm.saved)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun wordMissingFromDictionaryShowsMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeEditorBookRepo()
            val vm = WordSelectionEditorViewModel(FakeWordRepo(null), repo)
            vm.load(5L, 999L, "zzz")
            advanceUntilIdle()

            assertTrue(vm.loaded)
            assertNull(vm.detail)
            assertEquals("词库中找不到「zzz」", vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
