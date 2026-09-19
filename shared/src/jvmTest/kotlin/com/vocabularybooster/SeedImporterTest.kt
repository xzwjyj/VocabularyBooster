package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryWord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 种子装载端到端（真实 SEED_DICTIONARY_JSON + 幂等 + FR-16 可插拔）。 */
class SeedImporterTest {

    private fun newImporter(db: TestDb): SeedImporter =
        SeedImporter(db.database, FixedClock(), DispatchersForTest)

    @Test
    fun realSeedImportsOnceThenReusesAndEnsureSeededIsIdempotent() = runTest {
        val db = TestDb.inMemory()
        val provider = SeedDictionaryProvider(SEED_DICTIONARY_JSON)
        val importer = newImporter(db)

        val first = importer.ensureSeeded(provider)
        assertEquals(provider.loadAll().size, first!!.insertedWords)
        assertEquals(0, first.reusedWords)
        assertEquals(provider.loadAll().size.toLong(), db.database.wordQueries.countAll().executeAsOne())

        // 二次导入：全部复用，绝不重复建词（FR-5 复用原则）
        val second = importer.import(provider.loadAll())
        assertEquals(0, second.insertedWords)
        assertEquals(provider.loadAll().size, second.reusedWords)
        assertEquals(provider.loadAll().size.toLong(), db.database.wordQueries.countAll().executeAsOne())

        // ensureSeeded：库非空 → null
        assertNull(importer.ensureSeeded(provider))
    }

    @Test
    fun importedSeedServesLookupThroughRepository() = runTest {
        val db = TestDb.inMemory()
        val provider = SeedDictionaryProvider(SEED_DICTIONARY_JSON)
        newImporter(db).ensureSeeded(provider)

        val repo = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest)
        val detail = repo.lookup("  BOOST ")!!
        assertEquals("boost", detail.word.text)
        // 种子契约：每条释义至少 1 例句
        detail.entries.forEach { entry ->
            assertTrue(detail.examplesByEntryId[entry.definitionEntryId].orEmpty().isNotEmpty())
        }
        assertTrue(detail.entries.map { it.partOfSpeech }.containsAll(listOf("verb", "noun")))

        // 前缀搜索覆盖多个种子词
        assertTrue(repo.search("ab").map { it.text }.containsAll(listOf("abandon", "absorb")))
    }

    @Test
    fun stubProviderImportDemonstratesPluggability() = runTest {
        // FR-16：换一个桩数据源，导入与查询链路零改动
        val stubWords = listOf(
            DictionaryWord(
                text = "StubWord",
                ipaAm = "/stʌb/",
                definitions = listOf(
                    DictionaryDefinitionEntry(
                        partOfSpeech = "noun",
                        partOfSpeechOrder = 1,
                        definitionOrder = 1,
                        meaningEN = "a word from a stub provider",
                        meaningCN = "来自桩数据源的词",
                        examples = listOf(
                            DictionaryExample(sentence = "A stub word.", chineseTranslation = "一个桩词。"),
                        ),
                    ),
                ),
            ),
        )
        val db = TestDb.inMemory()
        val report = newImporter(db).import(stubWords)

        assertEquals(1, report.insertedWords)
        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("stubword")!!
        assertEquals("StubWord", detail.word.text) // 原文保留大小写，归一化命中
        assertEquals(1, detail.entries.size)
        assertEquals(1, detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!.size)
    }
}
