package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.model.ExampleSourceType
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

    // ---- 增强回填（Phase 8.6 Tatoeba/音标，FR-18）----

    /** 例句增强前导入的词（零例句）→ backfillEnhancements 补齐到首释义，幂等。 */
    @Test
    fun backfillEnhancementsBackfillsMissingExamplesAndIsIdempotent() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        // 旧导入：无例句（Tatoeba 增强前的形态）
        importer.import(
            listOf(
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "the effect of one's actions", "业；因果报应"),
                        DictionaryDefinitionEntry("noun", 1, 1, "fate", "命运"),
                    ),
                ),
            ),
        )

        // 词典源：2 条例句挂首释义
        val enriched = DictionaryWord(
            text = "karma",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "the effect of one's actions", "业；因果报应",
                    examples = listOf(
                        DictionaryExample("Karma caught up with him.", "因果报应找上了他。", sourceType = "TATOEBA"),
                        DictionaryExample("She believes in karma.", "她相信因果。", sourceType = "TATOEBA"),
                    ),
                ),
                DictionaryDefinitionEntry("noun", 1, 1, "fate", "命运"),
            ),
        )
        assertEquals(2, importer.backfillEnhancements(enriched))

        val repo = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest)
        val detail = repo.lookup("karma")!!
        val firstEntryExamples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        val secondEntryExamples = detail.examplesByEntryId[detail.entries[1].definitionEntryId].orEmpty()
        assertEquals(2, firstEntryExamples.size) // 挂首释义
        assertTrue(secondEntryExamples.isEmpty())
        assertEquals(ExampleSourceType.TATOEBA, firstEntryExamples[0].sourceType)
        assertEquals("因果报应找上了他。", firstEntryExamples[0].chineseTranslation)

        // 幂等：同句已存在 → 0 变更，不重复插入
        assertEquals(0, importer.backfillEnhancements(enriched))
    }

    /** 旧单串格式资产导入的 Tatoeba 例句（译文空）→ 只补译文，不重复建行。 */
    @Test
    fun backfillEnhancementsFillsBlankTranslationWithoutDuplicating() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "the effect of one's actions", "业；因果报应",
                            examples = listOf(
                                DictionaryExample("Karma caught up with him.", "", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val withZh = DictionaryWord(
            text = "karma",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "the effect of one's actions", "业；因果报应",
                    examples = listOf(
                        DictionaryExample("Karma caught up with him.", "因果报应找上了他。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(withZh)) // 只更新译文

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("karma")!!
        val examples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        assertEquals(1, examples.size) // 不重复建行
        assertEquals("因果报应找上了他。", examples[0].chineseTranslation)
    }

    /** 未导入的词 → 回填返回 0，绝不建词（建词只属 import）。 */
    @Test
    fun backfillEnhancementsSkipsUnknownWord() = runTest {
        val db = TestDb.inMemory()
        val enriched = DictionaryWord(
            text = "ghost",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "spirit", "鬼魂",
                    examples = listOf(DictionaryExample("A ghost appeared.", "一只鬼出现了。")),
                ),
            ),
        )
        assertEquals(0, newImporter(db).backfillEnhancements(enriched))
        assertEquals(0L, db.database.wordQueries.countAll().executeAsOne())
    }

    /** 增强前导入的词（音标空）→ 补音标；已有音标不覆盖；幂等。 */
    @Test
    fun backfillEnhancementsBackfillsMissingIpaWithoutOverwriting() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                // 旧导入形态：无音标
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "fate", "因果"),
                    ),
                ),
                // 已有音标：不得被覆盖
                DictionaryWord(
                    text = "old",
                    ipaAm = "/əʊld/",
                    definitions = listOf(
                        DictionaryDefinitionEntry("adjective", 2, 0, "not new", "旧的"),
                    ),
                ),
            ),
        )

        val dictKarma = DictionaryWord(
            text = "karma",
            ipaAm = "/ˈkɑːmə/",
            definitions = listOf(
                DictionaryDefinitionEntry("noun", 1, 0, "fate", "因果"),
            ),
        )
        val dictOld = DictionaryWord(
            text = "old",
            ipaAm = "/oʊld/", // 词典同词不同记法——已有值优先
            definitions = listOf(
                DictionaryDefinitionEntry("adjective", 2, 0, "not new", "旧的"),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dictKarma)) // 只补音标
        assertEquals(0, importer.backfillEnhancements(dictOld)) // 已有音标 → 不覆盖

        val repo = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest)
        assertEquals("/ˈkɑːmə/", repo.lookup("karma")!!.word.ipaAm)
        assertEquals("/əʊld/", repo.lookup("old")!!.word.ipaAm)

        // 幂等：音标已补 → 0 变更
        assertEquals(0, importer.backfillEnhancements(dictKarma))
    }

    /** 零释义词（TXT 导入形态，FR-14）→ 音标仍可回填（不因无释义提前放弃）。 */
    @Test
    fun backfillEnhancementsBackfillsIpaForWordWithoutDefinitions() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(text = "solo", definitions = emptyList()),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(DictionaryWord(text = "solo", ipaAm = "/ˈsoʊloʊ/")))
        val word = db.database.wordQueries.selectByNormalizedText("solo").executeAsOne()
        assertEquals("/ˈsoʊloʊ/", word.ipaAm)
    }

    /** lookup 钩子：DB 已有无例句旧词 → 查看详情时自动从词典源回填（端到端）。 */
    @Test
    fun lookupBackfillsExamplesFromDictionaryProviderOnDemand() = runTest {
        val db = TestDb.inMemory()
        // 旧导入：零例句
        newImporter(db).import(
            listOf(
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "the effect of one's actions", "业；因果报应"),
                    ),
                ),
            ),
        )
        val dictProvider = object : DictionaryProvider {
            override suspend fun lookup(text: String): DictionaryWord? =
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "the effect of one's actions", "业；因果报应",
                            examples = listOf(
                                DictionaryExample("She believes in karma.", "她相信因果。", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                )
        }
        val repo = SqlDelightWordRepository(
            database = db.database,
            dictionaryProvider = dictProvider,
            seedImporter = newImporter(db),
            dispatcher = DispatchersForTest,
        )

        val detail = repo.lookup("karma")!!
        val examples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        assertEquals(1, examples.size)
        assertEquals("她相信因果。", examples[0].chineseTranslation)
    }

    /** lookup 钩子（端到端）：例句/译文已完整但音标缺失 → 查看详情时自动补音标。 */
    @Test
    fun lookupBackfillsMissingIpaFromDictionaryProvider() = runTest {
        val db = TestDb.inMemory()
        // 旧导入形态：例句完整（例句闸门不触发）、音标空
        newImporter(db).import(
            listOf(
                DictionaryWord(
                    text = "karma",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "fate", "因果",
                            examples = listOf(
                                DictionaryExample("She believes in karma.", "她相信因果。", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val dictProvider = object : DictionaryProvider {
            override suspend fun lookup(text: String): DictionaryWord? =
                DictionaryWord(
                    text = "karma",
                    ipaAm = "/ˈkɑːmə/",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "fate", "因果",
                            examples = listOf(
                                DictionaryExample("She believes in karma.", "她相信因果。", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                )
        }
        val repo = SqlDelightWordRepository(
            database = db.database,
            dictionaryProvider = dictProvider,
            seedImporter = newImporter(db),
            dispatcher = DispatchersForTest,
        )
        assertEquals("/ˈkɑːmə/", repo.lookup("karma")!!.word.ipaAm)
    }
}
