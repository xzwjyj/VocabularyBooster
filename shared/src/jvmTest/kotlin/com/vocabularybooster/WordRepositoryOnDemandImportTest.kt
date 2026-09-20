package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按需导入（FR-18，Phase 8.6；TEST_PLAN §4.8 词典组）：真实 JDBC 库 + 真实 SeedImporter，
 * DictionaryProvider 用 Fake（Android 资产侧另有 BundledDictionarySmokeTest 走真 asset）。
 * 边界：源命中 → 导入后返回且二次查询走 DB / 源未收录 → null 且库零增长 /
 * DB 已有 → 不再打扰词典源。
 */
class WordRepositoryOnDemandImportTest {

    /** 记录调用数的词典源 Fake：hit 返回固定词条，miss 返回 null，hit 也可抛（证 DB 命中不打扰）。 */
    private class FakeProvider(
        private val word: DictionaryWord? = null,
        private val explodeOnCall: Boolean = false,
    ) : DictionaryProvider {
        var calls: Int = 0
            private set

        override suspend fun lookup(text: String): DictionaryWord? {
            calls++
            if (explodeOnCall) error("词典源不应被调用（DB 已命中）")
            return word
        }
    }

    private lateinit var db: TestDb
    private lateinit var importer: SeedImporter

    private val bundledWord = DictionaryWord(
        text = "Serendipity",
        ipaAm = "ˌserənˈdipəti",
        definitions = listOf(
            DictionaryDefinitionEntry(
                partOfSpeech = "noun",
                partOfSpeechOrder = 1,
                definitionOrder = 0,
                meaningEN = "good luck in making unexpected and fortunate discoveries",
                meaningCN = "偶然发现珍宝的运气",
                examples = listOf(
                    // 完整词条（含例句）：lookup 不触发例句回填，DB 命中后不再打扰词典源
                    DictionaryExample(
                        sentence = "Finding this beach was pure serendipity.",
                        chineseTranslation = "找到这片海滩纯属意外之喜。",
                        sourceType = "TATOEBA",
                    ),
                ),
            ),
        ),
    )

    @BeforeTest
    fun setUp() {
        db = TestDb.inMemory()
        importer = SeedImporter(db.database, FixedClock(), DispatchersForTest)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun dbMissWithProviderHitImportsAndSecondLookupServesFromDb() = runTest {
        val provider = FakeProvider(word = bundledWord)
        val repo = SqlDelightWordRepository(db.database, provider, importer, DispatchersForTest)

        val first = repo.lookup("  serendipity ") // 端口契约：大小写/首尾空白不敏感
        assertNotNull(first)
        assertEquals("Serendipity", first.word.text) // 原始大小写保留
        assertEquals(1, first.entries.size)
        assertEquals(1L, db.database.wordQueries.countAll().executeAsOne())

        val second = repo.lookup("serendipity")
        assertNotNull(second)
        assertEquals(1, provider.calls) // 二次查询由 DB 命中，不再打扰词典源
    }

    @Test
    fun dbMissWithProviderMissReturnsNullAndDbStaysEmpty() = runTest {
        val repo = SqlDelightWordRepository(
            db.database,
            FakeProvider(word = null),
            importer,
            DispatchersForTest,
        )

        assertNull(repo.lookup("zzz-not-in-dictionary"))
        assertEquals(0L, db.database.wordQueries.countAll().executeAsOne())
    }

    @Test
    fun dbHitNeverConsultsProvider() = runTest {
        // 完整词条（含例句）不触发回填；例句缺失时的回填咨询由 SeedImporterTest 覆盖
        importer.import(listOf(bundledWord))
        val repo = SqlDelightWordRepository(
            db.database,
            FakeProvider(explodeOnCall = true),
            importer,
            DispatchersForTest,
        )

        val detail = repo.lookup("serendipity")
        assertNotNull(detail)
        assertTrue(detail.entries.isNotEmpty())
    }
}
