package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.repository.WordRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WordRepository（FR-1 查词 + 前缀搜索）。 */
class WordRepositoryTest {

    private fun newRepo(db: TestDb): WordRepository = SqlDelightWordRepository(db.database, DispatchersForTest)

    /** 词 + 2 释义（noun 先插、verb 后插，验证 Q1 排序与插入序无关）+ 2 例句。 */
    private fun TestDb.seedBoostFamily() {
        val now = 1_760_000_000_000L
        database.wordQueries.insertWord("boost", "boost", "/buːst/", null, null, now, now)
        val boostId = database.wordQueries.selectLastInsertRowId().executeAsOne()
        database.definitionEntryQueries.insertDefinitionEntry(boostId, "noun", 1, 1, "one that boosts", "推动者")
        database.definitionEntryQueries.insertDefinitionEntry(boostId, "verb", 0, 1, "to push up", "增强")
        val defs = database.definitionEntryQueries.selectDefinitionsForWord(boostId).executeAsList()
        val verbDef = defs.first { it.partOfSpeech == "verb" }
        database.exampleQueries.insertExample(verbDef.definitionEntryId, "Sales boosted.", "销售提升了。", "TTS", null, null, null, null, 0)
        database.exampleQueries.insertExample(
            verbDef.definitionEntryId,
            "It was a truth acknowledged.",
            "这是公认的事实。",
            "LICENSED_OTHER",
            "Pride and Prejudice (1813)",
            "Public domain",
            null,
            null,
            1,
        )
        database.wordQueries.insertWord("booster", "booster", null, null, null, now, now)
        database.wordQueries.insertWord("boundary", "boundary", null, null, null, now, now)
    }

    @Test
    fun lookupIsCaseInsensitiveAndTrimmed() = runTest {
        val db = TestDb.inMemory()
        db.seedBoostFamily()
        val repo = newRepo(db)

        val detail = repo.lookup("  BOOST ")!!
        assertEquals("boost", detail.word.text)
        assertEquals("/buːst/", detail.word.ipaAm)

        // Q1：verb(0) 在 noun(1) 前——尽管 noun 先插入（I-4 与插入序无关）
        assertEquals(listOf("verb", "noun"), detail.entries.map { it.partOfSpeech })

        // 例句按 exampleOrder 挂到所属释义，来源类型如实映射
        val verbEntry = detail.entries.first { it.partOfSpeech == "verb" }
        val examples = detail.examplesByEntryId[verbEntry.definitionEntryId].orEmpty()
        assertEquals(2, examples.size)
        assertEquals(ExampleSourceType.TTS, examples[0].sourceType)
        assertEquals(ExampleSourceType.LICENSED_OTHER, examples[1].sourceType)
        assertEquals("Pride and Prejudice (1813)", examples[1].sourceRef)
    }

    @Test
    fun lookupUnknownWordReturnsNull() = runTest {
        val db = TestDb.inMemory()
        db.seedBoostFamily()
        assertNull(newRepo(db).lookup("nonexistent"))
        assertNull(newRepo(db).lookup("   "))
    }

    @Test
    fun searchMatchesPrefixShortFirstAndHonorsLimit() = runTest {
        val db = TestDb.inMemory()
        db.seedBoostFamily()
        val repo = newRepo(db)

        assertEquals(listOf("boost", "booster"), repo.search("boo").map { it.text })
        assertEquals(listOf("boost", "booster", "boundary"), repo.search("bo").map { it.text })
        assertEquals(listOf("boost"), repo.search("boo", limit = 1).map { it.text })
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.search("zzz").isEmpty())
    }
}
