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
        // FR-22 种子契约：59 词全带英音音标（boost 原缺，v1.18 起补齐）
        assertTrue(
            provider.loadAll().all { !it.ipaBr.isNullOrBlank() },
            "种子词必须全部携带 ipaBr",
        )
        assertEquals("/buːst/", detail.word.ipaBr)
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

    // ---- 英音回填（FR-22，同「只补空缺」口径）----

    /** 英音缺失 → 补 ipaBr；已有英音不覆盖；幂等（与美音组同语义）。 */
    @Test
    fun backfillEnhancementsBackfillsMissingIpaBrWithoutOverwriting() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                // 旧导入形态：只有美音
                DictionaryWord(
                    text = "karma",
                    ipaAm = "/ˈkɑːrmə/",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "fate", "因果"),
                    ),
                ),
                // 已有英音：不得被覆盖
                DictionaryWord(
                    text = "old",
                    ipaAm = "/oʊld/",
                    ipaBr = "/əʊld/",
                    definitions = listOf(
                        DictionaryDefinitionEntry("adjective", 2, 0, "not new", "旧的"),
                    ),
                ),
            ),
        )

        val dictKarma = DictionaryWord(
            text = "karma",
            ipaAm = "/ˈkɑːrmə/",
            ipaBr = "/ˈkɑːmə/",
            definitions = listOf(
                DictionaryDefinitionEntry("noun", 1, 0, "fate", "因果"),
            ),
        )
        val dictOld = DictionaryWord(
            text = "old",
            ipaAm = "/oʊld/",
            ipaBr = "/ɒld/", // 词典同词不同记法——已有值优先
            definitions = listOf(
                DictionaryDefinitionEntry("adjective", 2, 0, "not new", "旧的"),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dictKarma)) // 只补英音（美音已在）
        assertEquals(0, importer.backfillEnhancements(dictOld)) // 已有英音 → 不覆盖

        val repo = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest)
        assertEquals("/ˈkɑːmə/", repo.lookup("karma")!!.word.ipaBr)
        assertEquals("/əʊld/", repo.lookup("old")!!.word.ipaBr)

        // 幂等：英音已补 → 0 变更
        assertEquals(0, importer.backfillEnhancements(dictKarma))
    }

    /** lookup 钩子（端到端，FR-22 闸门）：例句/美音完整但英音缺失 → 查看详情时自动补英音。 */
    @Test
    fun lookupBackfillsMissingIpaBrFromDictionaryProvider() = runTest {
        val db = TestDb.inMemory()
        // 旧导入形态：例句 + 美音完整（既有闸门不触发）、英音空
        newImporter(db).import(
            listOf(
                DictionaryWord(
                    text = "karma",
                    ipaAm = "/ˈkɑːrmə/",
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
                    ipaAm = "/ˈkɑːrmə/",
                    ipaBr = "/ˈkɑːmə/",
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
        assertEquals("/ˈkɑːmə/", repo.lookup("karma")!!.word.ipaBr)
    }

    // ---- 例句重归位（SCR-SENSEATTR v6：词典例句逐释义归属 diff）----

    /** 对齐词条：词典例句挂到自己的释义（defIdx=1 的例句不落首释义）；幂等。 */
    @Test
    fun reattributeInsertsExampleAtItsOwnSense() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "land along a river", "河岸"),
                        DictionaryDefinitionEntry("verb", 0, 0, "to deposit money", "存入银行"),
                    ),
                ),
            ),
        )

        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry("noun", 1, 0, "land along a river", "河岸"),
                DictionaryDefinitionEntry(
                    "verb", 0, 0, "to deposit money", "存入银行",
                    examples = listOf(
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dict))

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        // Q1 排序：verb(0) 先于 noun(1)——entries[0]=存入银行，entries[1]=河岸
        val verbEntry = detail.entries.first { it.partOfSpeech == "verb" }
        val nounEntry = detail.entries.first { it.partOfSpeech == "noun" }
        assertEquals(1, detail.examplesByEntryId[verbEntry.definitionEntryId]!!.size)
        assertTrue(detail.examplesByEntryId[nounEntry.definitionEntryId].orEmpty().isEmpty())

        assertEquals(0, importer.backfillEnhancements(dict))
    }

    /** 对齐词条：归属不同的既有例句 → 移动（exampleId 不变——用户勾选行天然保留）；幂等。 */
    @Test
    fun reattributeMovesMisplacedExampleKeepingExampleId() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        // 旧导入形态：例句全挂首释义（v5 前的遗留归属）
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "land along a river", "河岸",
                            examples = listOf(
                                DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                            ),
                        ),
                        DictionaryDefinitionEntry("verb", 0, 0, "to deposit money", "存入银行"),
                    ),
                ),
            ),
        )
        val repo = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest)
        val before = repo.lookup("bank")!!
        val exampleIdBefore = before.examplesByEntryId.values.flatten().single().exampleId

        // v6 词典：同一句归属 verb 释义
        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry("noun", 1, 0, "land along a river", "河岸"),
                DictionaryDefinitionEntry(
                    "verb", 0, 0, "to deposit money", "存入银行",
                    examples = listOf(
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dict)) // 只移动，不重复建行

        val after = repo.lookup("bank")!!
        val moved = after.examplesByEntryId.values.flatten().single()
        assertEquals(exampleIdBefore, moved.exampleId) // exampleId 稳定 → 勾选行经 exampleId 关联天然保留
        assertEquals(
            after.entries.first { it.partOfSpeech == "verb" }.definitionEntryId,
            moved.definitionEntryId,
        )

        assertEquals(0, importer.backfillEnhancements(dict))
    }

    /** 译文覆盖只作用于词典来源例句（TATOEBA/AI_GENERATED）；视频例句译文绝不覆盖。 */
    @Test
    fun reattributeOverwritesTranslationOnlyForDictionarySourcedExamples() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "land along a river", "河岸",
                            examples = listOf(
                                // 视频例句：译文不得被词典覆盖
                                DictionaryExample(
                                    "The river overflowed the bank.", "河水漫过了堤岸（原视频字幕）。",
                                    sourceType = "REAL_MOVIE_TV",
                                ),
                                // 词典例句：译文陈旧 → 覆盖
                                DictionaryExample(
                                    "She banked her salary.", "旧机翻译文。",
                                    sourceType = "TATOEBA",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "land along a river", "河岸",
                    examples = listOf(
                        DictionaryExample(
                            "The river overflowed the bank.", "河水漫过堤岸。",
                            sourceType = "TATOEBA",
                        ),
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dict)) // 只覆盖 TATOEBA 行

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        val examples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        assertEquals(2, examples.size) // 不重复建行
        assertEquals(
            "河水漫过了堤岸（原视频字幕）。",
            examples.first { it.sentence == "The river overflowed the bank." }.chineseTranslation,
        )
        assertEquals(
            "她把工资存入了银行。",
            examples.first { it.sentence == "She banked her salary." }.chineseTranslation,
        )

        assertEquals(0, importer.backfillEnhancements(dict))
    }

    /** 对齐词条：词典已剔除的词典例句无勾选引用 → 删除；非词典来源例句永不删。 */
    @Test
    fun reattributeDeletesStaleDictionaryExampleOnlyWithoutSelection() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "land along a river", "河岸",
                            examples = listOf(
                                DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                                // 词典已剔除的短语改挂句（v6 归属重排产物）
                                DictionaryExample("She banks with HSBC.", "她的账户开在汇丰。", sourceType = "TATOEBA"),
                                // 非词典来源：词典没有此句也不删
                                DictionaryExample("Go to the bank!", "去银行吧！", sourceType = "TTS"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // v6 词典只保留一句
        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "land along a river", "河岸",
                    examples = listOf(
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dict))

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        val examples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        assertEquals(listOf("She banked her salary.", "Go to the bank!"), examples.map { it.sentence })

        assertEquals(0, importer.backfillEnhancements(dict))
    }

    /** 删除守卫：陈旧词典例句被词条例句勾选行引用 → 保留不删（宁留不错删，FR-5）。 */
    @Test
    fun reattributeKeepsStaleExampleReferencedBySelection() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "land along a river", "河岸",
                            examples = listOf(
                                DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                                DictionaryExample("She banks with HSBC.", "她的账户开在汇丰。", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // 用户勾选了将被剔除的例句
        val wordId = db.database.wordQueries.selectByNormalizedText("bank").executeAsOne().wordId
        val staleExampleId = db.database.exampleQueries
            .selectExamplesForEntry(
                db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList().single().definitionEntryId,
            )
            .executeAsList()
            .first { it.sentence == "She banks with HSBC." }
            .exampleId
        db.database.wordBookQueries.insertOriginalWordBook("Guard", null, 0L, 0L)
        val wordBookId = db.database.wordBookQueries.selectLastInsertRowId().executeAsOne()
        db.database.wordBookEntryQueries.insertEntry(wordBookId, wordId, 0L, null, 0L)
        val entryId = db.database.wordBookEntryQueries.selectLastInsertRowId().executeAsOne()
        db.database.wordBookEntryExampleSelectionQueries.insertExampleSelection(entryId, staleExampleId)

        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "land along a river", "河岸",
                    examples = listOf(
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(0, importer.backfillEnhancements(dict)) // 有引用 → 零删除零写入

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        assertEquals(2, detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!.size)
    }

    /** 生成兜底句如实标注：sourceType = AI_GENERATED（SCR-SENSEATTR 完整覆盖兜底，FR-3）。 */
    @Test
    fun reattributeInsertsGeneratedExampleWithAiGeneratedSourceType() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry("noun", 1, 0, "land along a river", "河岸"),
                    ),
                ),
            ),
        )

        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    "noun", 1, 0, "land along a river", "河岸",
                    examples = listOf(
                        DictionaryExample(
                            "We had a picnic on the bank of the Thames.",
                            "我们在泰晤士河岸边野餐。",
                            sourceType = "AI_GENERATED",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, importer.backfillEnhancements(dict))

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        val example = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!.single()
        assertEquals(ExampleSourceType.AI_GENERATED, example.sourceType)
        assertEquals("我们在泰晤士河岸边野餐。", example.chineseTranslation)
    }

    /** 非对齐词条（TXT/视频导入，释义结构与词典不一致）：保持旧语义——不移动、不删除。 */
    @Test
    fun reattributeKeepsLegacySemanticsForNonAlignedWord() = runTest {
        val db = TestDb.inMemory()
        val importer = newImporter(db)
        importer.import(
            listOf(
                DictionaryWord(
                    text = "bank",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            "noun", 1, 0, "land along a river", "河岸",
                            examples = listOf(
                                DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                                DictionaryExample("She banks with HSBC.", "她的账户开在汇丰。", sourceType = "TATOEBA"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 词典 2 释义（DB 只有 1 → 非对齐）：一句归属第二释义、一句已剔除——都不生效
        val dict = DictionaryWord(
            text = "bank",
            definitions = listOf(
                DictionaryDefinitionEntry("noun", 1, 0, "land along a river", "河岸"),
                DictionaryDefinitionEntry(
                    "verb", 0, 0, "to deposit money", "存入银行",
                    examples = listOf(
                        DictionaryExample("She banked her salary.", "她把工资存入了银行。", sourceType = "TATOEBA"),
                    ),
                ),
            ),
        )
        assertEquals(0, importer.backfillEnhancements(dict)) // 不移动、不删除、无新句

        val detail = SqlDelightWordRepository(db.database, dispatcher = DispatchersForTest).lookup("bank")!!
        val examples = detail.examplesByEntryId[detail.entries[0].definitionEntryId]!!
        assertEquals(2, examples.size) // 两句原位保留（首释义）
    }
}
