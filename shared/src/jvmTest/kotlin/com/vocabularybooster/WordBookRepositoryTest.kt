package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.WordBookType
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.domain.repository.WordBookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * WordBookRepository（FR-4/FR-5）：CRUD、成员关系、保存流校验、删除守卫、
 * 多本共享词、删本不删词、重开持久化。
 */
class WordBookRepositoryTest {

    private fun newRepo(db: TestDb, clock: FixedClock = FixedClock()): WordBookRepository =
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)

    /** 词 boost：verb(2 条释义，各带 1 例句) + noun(1 条)。返回 (wordId, verbDef1Id, verbDef2Id, nounDefId, 例句id列表)。 */
    private data class SeededWord(
        val wordId: Long,
        val verbDef1: Long,
        val verbDef2: Long,
        val nounDef: Long,
        val def1Examples: List<Long>,
        val def2Examples: List<Long>,
    )

    private fun TestDb.seedWord(): SeededWord {
        // 文件库 + ThreadedConnectionManager：插入与 last_insert_rowid 必须同事务同连接，
        // 否则 SELECT 落在新连接上恒为 0（JVM 驱动行为，Android 单连接不受影响）
        var result: SeededWord? = null
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordQueries.insertWord("boost", "boost", null, null, null, now, now)
            val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
            database.definitionEntryQueries.insertDefinitionEntry(wordId, "verb", 0, 1, "to push up", "增强")
            val verbDef1 = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
            database.definitionEntryQueries.insertDefinitionEntry(wordId, "verb", 0, 2, "to promote", "宣传推广")
            val verbDef2 = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
            database.definitionEntryQueries.insertDefinitionEntry(wordId, "noun", 1, 1, "a boost", "一次提升")
            val nounDef = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
            fun examplesOf(def: Long, count: Int): List<Long> =
                (0 until count).map { i ->
                    database.exampleQueries.insertExample(def, "sentence $i", "例句 $i", "TTS", null, null, null, null, i.toLong())
                    database.exampleQueries.selectExamplesForEntry(def).executeAsList()
                        .single { it.sentence == "sentence $i" }.exampleId
                }
            result = SeededWord(wordId, verbDef1, verbDef2, nounDef, examplesOf(verbDef1, 2), examplesOf(verbDef2, 1))
        }
        return result!!
    }

    @Test
    fun wordBookCrudWorks() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)

        val id1 = repo.createWordBook("  TOEFL  ")
        val id2 = repo.createWordBook("GRE")
        assertEquals(2, repo.getWordBooks().size)
        assertEquals("TOEFL", repo.getWordBooks().first { it.wordBook.wordBookId == id1 }.wordBook.name)
        assertEquals(WordBookType.ORIGINAL, repo.getWordBooks().first { it.wordBook.wordBookId == id1 }.wordBook.type)

        repo.renameWordBook(id1, "TOEFL Core")
        assertEquals("TOEFL Core", repo.getWordBooks().first { it.wordBook.wordBookId == id1 }.wordBook.name)

        repo.deleteWordBook(id2)
        assertEquals(listOf(id1), repo.getWordBooks().map { it.wordBook.wordBookId })

        // 空白名拒绝；重命名不存在的本拒绝（FR-4）
        assertFailsWith<RepositoryValidationException> { repo.createWordBook("   ") }
        assertFailsWith<RepositoryValidationException> { repo.renameWordBook(999L, "x") }

        // 观察流初值可用（Q6 响应式）
        assertEquals(1, repo.observeWordBooks().first().size)
    }

    @Test
    fun saveFlowWritesSelectionsPerBookIndependently() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val w = db.seedWord()
        val bookA = repo.createWordBook("A")
        val bookB = repo.createWordBook("B")

        // FR-5：一次请求 = 一套选择集写入多个本；每本集合要不同 → 分次保存
        repo.saveWordToBooks(
            SaveWordRequest(
                wordId = w.wordId,
                wordBookIds = listOf(bookA),
                selections = listOf(
                    DefinitionSelection(w.verbDef1, listOf(w.def1Examples[0])),
                    DefinitionSelection(w.verbDef2),
                    DefinitionSelection(w.nounDef),
                ),
            ),
        )
        repo.saveWordToBooks(
            SaveWordRequest(
                wordId = w.wordId,
                wordBookIds = listOf(bookB),
                selections = listOf(DefinitionSelection(w.nounDef)),
            ),
        )

        val summaries = repo.getWordBooks().associateBy { it.wordBook.wordBookId }
        assertEquals(1, summaries.getValue(bookA).entryCount)
        assertEquals(1, summaries.getValue(bookB).entryCount)

        val entryA = db.database.wordBookEntryQueries.selectEntryByWord(bookA, w.wordId).executeAsOne()
        val entryB = db.database.wordBookEntryQueries.selectEntryByWord(bookB, w.wordId).executeAsOne()

        // 本 A：3 条释义选择；释义1 恰选 1 例句，释义2/3 无例句选择
        assertEquals(3, db.database.wordBookEntryDefinitionQueries.selectEntryDefinitions(entryA.wordBookEntryId).executeAsList().size)
        val aExamples = db.database.wordBookEntryExampleSelectionQueries.selectExampleSelections(entryA.wordBookEntryId).executeAsList()
        assertEquals(listOf(w.def1Examples[0]), aExamples.map { it.exampleId })

        // 本 B：仅释义3（例句为空集 = 只存释义，PROJECT_SPEC v1.3）
        assertEquals(listOf(w.nounDef), db.database.wordBookEntryDefinitionQueries.selectEntryDefinitions(entryB.wordBookEntryId).executeAsList().map { it.definitionEntryId })
        assertTrue(db.database.wordBookEntryExampleSelectionQueries.selectExampleSelections(entryB.wordBookEntryId).executeAsList().isEmpty())
    }

    @Test
    fun saveFlowValidationRejectsIllegalSelectionsAtomically() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val w = db.seedWord()
        val book = repo.createWordBook("A")

        // 未选本 / 未选释义（FR-5）
        assertFailsWith<RepositoryValidationException> {
            repo.saveWordToBooks(SaveWordRequest(w.wordId, emptyList(), listOf(DefinitionSelection(w.verbDef1))))
        }
        assertFailsWith<RepositoryValidationException> {
            repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(book), emptyList()))
        }
        // 例句不属于所选释义
        assertFailsWith<RepositoryValidationException> {
            repo.saveWordToBooks(
                SaveWordRequest(w.wordId, listOf(book), listOf(DefinitionSelection(w.verbDef1, listOf(w.def2Examples[0])))),
            )
        }
        // 释义不属于该词
        assertFailsWith<RepositoryValidationException> {
            repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(book), listOf(DefinitionSelection(999L))))
        }
        // 原子性：包含不存在本的多本保存 → 整体失败，无半成品
        assertFailsWith<RepositoryValidationException> {
            repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(book, 888L), listOf(DefinitionSelection(w.verbDef1))))
        }
        assertEquals(0, repo.getWordBooks().first { it.wordBook.wordBookId == book }.entryCount)
    }

    @Test
    fun reSaveReplacesSelectionsAndKeepsEntryOrder() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val w = db.seedWord()
        val book = repo.createWordBook("A")
        repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(book), listOf(DefinitionSelection(w.verbDef1, w.def1Examples))))
        val first = db.database.wordBookEntryQueries.selectEntryByWord(book, w.wordId).executeAsOne()

        // 重存：改为仅释义2
        repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(book), listOf(DefinitionSelection(w.verbDef2))))
        val second = db.database.wordBookEntryQueries.selectEntryByWord(book, w.wordId).executeAsOne()

        assertEquals(first.entryOrder, second.entryOrder) // 队列位置不漂移
        assertEquals(1, db.database.wordBookEntryDefinitionQueries.selectEntryDefinitions(second.wordBookEntryId).executeAsList().size)
        assertTrue(db.database.wordBookEntryExampleSelectionQueries.selectExampleSelections(second.wordBookEntryId).executeAsList().isEmpty())
    }

    @Test
    fun multiWordBookSharesWordAndDeleteKeepsWord() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val w = db.seedWord()
        val bookA = repo.createWordBook("A")
        val bookB = repo.createWordBook("B")
        repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(bookA, bookB), listOf(DefinitionSelection(w.verbDef1, w.def1Examples))))

        // MULTI-WORDBOOK TEST：两本引用同一 Word；删 A 后 B 仍在，底层 Word 不删
        repo.deleteWordBook(bookA)

        assertEquals(1L, db.database.wordQueries.countAll().executeAsOne()) // Word 行未删
        val bWords = repo.getWordBookWords(bookB)
        assertEquals(listOf("boost"), bWords.map { it.wordText })
        assertEquals(1, db.database.wordBookEntryDefinitionQueries.selectEntryDefinitions(
            db.database.wordBookEntryQueries.selectEntryByWord(bookB, w.wordId).executeAsOne().wordBookEntryId,
        ).executeAsList().size)

        // 从 B 移除词：关系清干净（含选择与掌握行），词仍在
        db.database.wordMasteryQueries.markMastered(bookB, w.wordId, 1L)
        repo.removeWordFromWordBook(bookB, w.wordId)
        assertTrue(repo.getWordBookWords(bookB).isEmpty())
        assertEquals(0, db.database.wordBookEntryExampleSelectionQueries.selectExampleSelections(
            db.database.wordBookEntryQueries.selectEntryByWord(bookB, w.wordId).executeAsOneOrNull()?.wordBookEntryId ?: -1L,
        ).executeAsList().size)
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookB).executeAsOne())
        assertEquals(1L, db.database.wordQueries.countAll().executeAsOne())
    }

    @Test
    fun deleteGuardsRejectMedalAndDerivedParent() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val w = db.seedWord()
        val medalBook = repo.createWordBook("Medal")
        val parentBook = repo.createWordBook("Parent")
        val plainBook = repo.createWordBook("Plain")
        repo.saveWordToBooks(SaveWordRequest(w.wordId, listOf(medalBook, parentBook), listOf(DefinitionSelection(w.verbDef1))))

        val now = 1_760_000_000_000L
        // 守卫①：完成勋章
        db.database.achievementQueries.insertAchievement("BOOK_COMPLETED", medalBook, "{}", now)
        assertEquals(
            WordBookDeletionException.Reason.BOOK_HAS_COMPLETION_MEDAL,
            assertFailsWith<WordBookDeletionException> { repo.deleteWordBook(medalBook) }.reason,
        )

        // 守卫②：派生子本（需要一条会话行满足 FK）
        db.database.learningSessionQueries.insertSession(parentBook, "ABANDONED", 10, now, now)
        val sessionId = db.database.learningSessionQueries.selectLastInsertRowId().executeAsOne()
        db.database.wordBookQueries.insertDerivedWordBook("Parent 2026-09-01 08:30", null, parentBook, sessionId, now, now)
        assertEquals(
            WordBookDeletionException.Reason.HAS_DERIVED_CHILDREN,
            assertFailsWith<WordBookDeletionException> { repo.deleteWordBook(parentBook) }.reason,
        )

        // 无守卫的本正常删除；列表含派生子本（Q6 返回全部本）
        repo.deleteWordBook(plainBook)
        assertEquals(3, repo.getWordBooks().size)
    }

    @Test
    fun dataSurvivesCloseAndReopen() = runTest {
        // PERSISTENCE TEST：建本 → 加词（含选择）→ 关库 → 重开 → 数据仍在
        val db = TestDb.file()
        lateinit var seeded: SeededWord
        var bookId = 0L
        try {
            val repo = newRepo(db)
            seeded = db.seedWord()
            bookId = repo.createWordBook("Persist")
            repo.saveWordToBooks(
                SaveWordRequest(
                    wordId = seeded.wordId,
                    wordBookIds = listOf(bookId),
                    selections = listOf(
                        DefinitionSelection(seeded.verbDef1, seeded.def1Examples),
                        DefinitionSelection(seeded.nounDef),
                    ),
                ),
            )
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val repo2 = newRepo(reopened)
            val books = repo2.getWordBooks()
            assertEquals(1, books.size)
            assertEquals("Persist", books[0].wordBook.name)
            assertEquals(1, books[0].entryCount)

            val words = repo2.getWordBookWords(bookId)
            assertEquals(listOf("boost"), words.map { it.wordText })
            assertEquals(seeded.wordId, words[0].wordId)

            val entryId = reopened.database.wordBookEntryQueries
                .selectEntryByWord(bookId, seeded.wordId).executeAsOne().wordBookEntryId
            assertEquals(2, reopened.database.wordBookEntryDefinitionQueries
                .selectEntryDefinitions(entryId).executeAsList().size)
            assertEquals(
                seeded.def1Examples,
                reopened.database.wordBookEntryExampleSelectionQueries
                    .selectExampleSelections(entryId).executeAsList().map { it.exampleId },
            )
        } finally {
            reopened.close()
        }
    }
}
