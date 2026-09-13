package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightPlaybackContentRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PlaybackContentRepository（Phase 4 Step 1，AUDIO §2 装配输入 / Q4 + Q4b）：
 * 选中释义按 (partOfSpeechOrder, definitionOrder) 排序、选中例句按 exampleOrder ASC、
 * 未选择项不返回、导入词（零选择）空内容、(wordBookId, wordId) 边界与不存在 entry → null。
 * 真实 JDBC SQLite 集成（裁决要求：SQL 行为不得只以 Fake 验证）。
 */
class PlaybackContentRepositoryTest {

    private fun newContentRepo(db: TestDb): PlaybackContentRepository =
        SqlDelightPlaybackContentRepository(db.database, DispatchersForTest)

    private fun newBookRepo(db: TestDb): SqlDelightWordBookRepository =
        SqlDelightWordBookRepository(db.database, FixedClock(), DispatchersForTest)

    /** 词 boost：释义乱序入库（noun(1,1) → verb(0,2) → verb(0,1)），verb(0,1) 带 3 例句（exampleOrder 5/1/3 乱序）。 */
    private data class Seeded(
        val wordId: Long,
        val verbDef1: Long, // (partOfSpeechOrder=0, definitionOrder=1)
        val verbDef2: Long, // (0, 2)
        val nounDef: Long,  // (1, 1)
        val orderedExampleIds: List<Long>, // 期望顺序 exampleOrder 1/3/5
    )

    private fun TestDb.seedScrambledWord(): Seeded {
        var result: Seeded? = null
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordQueries.insertWord("boost", "boost", null, null, null, now, now)
            val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()

            fun def(pos: String, posOrder: Long, defOrder: Long, en: String, cn: String): Long {
                database.definitionEntryQueries.insertDefinitionEntry(wordId, pos, posOrder, defOrder, en, cn)
                return database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
            }

            val nounDef = def("noun", 1, 1, "a boost", "一次提升")   // 先入 noun
            val verbDef2 = def("verb", 0, 2, "to promote", "宣传推广") // 再入 verb(0,2)
            val verbDef1 = def("verb", 0, 1, "to push up", "增强")    // 最后 verb(0,1)

            fun example(def: Long, order: Long, tag: String): Long {
                database.exampleQueries.insertExample(def, "sentence-$tag", "例句-$tag", "TTS", null, null, null, null, order)
                return database.exampleQueries.selectExamplesForEntry(def).executeAsList()
                    .single { it.sentence == "sentence-$tag" }.exampleId
            }

            // 入库顺序 5 → 1 → 3：断言读取侧排序与入库顺序无关
            val ex5 = example(verbDef1, 5, "five")
            val ex1 = example(verbDef1, 1, "one")
            val ex3 = example(verbDef1, 3, "three")
            result = Seeded(wordId, verbDef1, verbDef2, nounDef, listOf(ex1, ex3, ex5))
        }
        return result!!
    }

    @Test
    fun selectedDefinitionsRespectPosAndDefOrderRegardlessOfInsertOrder() = runTest {
        val db = TestDb.inMemory()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val book = bookRepo.createWordBook("A")
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(book),
                selections = listOf(
                    DefinitionSelection(seeded.nounDef),
                    DefinitionSelection(seeded.verbDef2),
                    DefinitionSelection(seeded.verbDef1),
                ),
            ),
        )

        val content = newContentRepo(db).getPlaybackContent(book, seeded.wordId)!!
        assertEquals("boost", content.word.text)
        // Q4：(partOfSpeechOrder, definitionOrder) ASC——verb(0,1) → verb(0,2) → noun(1,1)
        assertEquals(
            listOf(seeded.verbDef1, seeded.verbDef2, seeded.nounDef),
            content.selectedDefinitions.map { it.definitionEntryId },
        )
    }

    @Test
    fun selectedExamplesRespectExampleOrderAsc() = runTest {
        val db = TestDb.inMemory()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val book = bookRepo.createWordBook("A")
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(book),
                selections = listOf(DefinitionSelection(seeded.verbDef1, seeded.orderedExampleIds)),
            ),
        )

        val examples = newContentRepo(db).getPlaybackContent(book, seeded.wordId)!!
            .examplesByDefinitionEntryId.getValue(seeded.verbDef1)
        assertEquals(seeded.orderedExampleIds, examples.map { it.exampleId })
        assertEquals(listOf(1, 3, 5), examples.map { it.exampleOrder })
    }

    @Test
    fun unselectedDefinitionsAndExamplesAreExcluded() = runTest {
        val db = TestDb.inMemory()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val book = bookRepo.createWordBook("A")
        // 只选 verbDef1 + 其 3 例句中的 1 句（exampleOrder=1）；verbDef2 / nounDef 不选
        val soleExample = seeded.orderedExampleIds.first()
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(book),
                selections = listOf(DefinitionSelection(seeded.verbDef1, listOf(soleExample))),
            ),
        )

        val content = newContentRepo(db).getPlaybackContent(book, seeded.wordId)!!
        assertEquals(listOf(seeded.verbDef1), content.selectedDefinitions.map { it.definitionEntryId })
        assertEquals(listOf(soleExample), content.examplesByDefinitionEntryId.getValue(seeded.verbDef1).map { it.exampleId })
        assertTrue(content.examplesByDefinitionEntryId.keys == setOf(seeded.verbDef1))
    }

    @Test
    fun definitionWithoutExampleSelectionHasNoExamples() = runTest {
        val db = TestDb.inMemory()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val book = bookRepo.createWordBook("A")
        // verbDef1 选中但零例句勾选 = 只保存释义（FR-5 允许空集）
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(book),
                selections = listOf(DefinitionSelection(seeded.verbDef1)),
            ),
        )

        val content = newContentRepo(db).getPlaybackContent(book, seeded.wordId)!!
        assertEquals(listOf(seeded.verbDef1), content.selectedDefinitions.map { it.definitionEntryId })
        assertTrue(content.examplesByDefinitionEntryId.isEmpty())
    }

    @Test
    fun importedWordWithoutAnySelectionYieldsEmptyContent() = runTest {
        val db = TestDb.inMemory()
        var wordId = 0L
        db.database.transaction {
            val now = 1_760_000_000_000L
            db.database.wordQueries.insertWord("apple", "apple", null, null, null, now, now)
            wordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
        }
        val book = newBookRepo(db).createWordBook("Imported")
        // 导入路径：裸 entry 行、零释义/例句选择（LE §10-3；空段裁决 L2 的输入形态）
        db.database.wordBookEntryQueries.insertEntry(book, wordId, 0, "苹果", 1_760_000_000_000L)

        val content = newContentRepo(db).getPlaybackContent(book, wordId)!!
        assertEquals("apple", content.word.text)
        assertTrue(content.selectedDefinitions.isEmpty())
        assertTrue(content.examplesByDefinitionEntryId.isEmpty())
    }

    @Test
    fun unknownBookOrWordOrMissingEntryReturnsNull() = runTest {
        val db = TestDb.inMemory()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val bookA = bookRepo.createWordBook("A")
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(bookA),
                selections = listOf(DefinitionSelection(seeded.nounDef)),
            ),
        )
        val bookB = bookRepo.createWordBook("B") // 存在但不含该词

        val repo = newContentRepo(db)
        assertNull(repo.getPlaybackContent(bookB, seeded.wordId)) // 词不在该本（entry 缺失）
        assertNull(repo.getPlaybackContent(999_999L, seeded.wordId)) // 本不存在
        assertNull(repo.getPlaybackContent(bookA, 999_999L)) // 词不存在
        assertEquals(1, repo.getPlaybackContent(bookA, seeded.wordId)!!.selectedDefinitions.size) // 对照组
    }

    @Test
    fun contentSurvivesCloseAndReopen() = runTest {
        val db = TestDb.file()
        val seeded = db.seedScrambledWord()
        val bookRepo = newBookRepo(db)
        val book = bookRepo.createWordBook("A")
        bookRepo.saveWordToBooks(
            SaveWordRequest(
                wordId = seeded.wordId,
                wordBookIds = listOf(book),
                selections = listOf(DefinitionSelection(seeded.verbDef1, seeded.orderedExampleIds)),
            ),
        )
        db.close()

        val reopened = TestDb.fileExisting(db.path!!)
        val content = newContentRepo(reopened).getPlaybackContent(book, seeded.wordId)!!
        assertEquals(listOf(seeded.verbDef1), content.selectedDefinitions.map { it.definitionEntryId })
        assertEquals(3, content.examplesByDefinitionEntryId.getValue(seeded.verbDef1).size)
        reopened.close()
    }
}
