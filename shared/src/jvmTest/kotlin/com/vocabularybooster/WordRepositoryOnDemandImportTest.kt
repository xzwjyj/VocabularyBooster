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
 * 边界：源命中 → 导入后返回且二次查询不重复导入 / 源未收录 → null 且库零增长 /
 * DB 已有非词典增强词 → 不再打扰词典源（SCR-SENSEATTR v6：词典例句词每次 lookup
 * 走幂等重归位 diff——仍咨询词典源，但收敛后零写入，不重复导入）。
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
        ipaBr = "ˌserənˈdɪpəti", // FR-22：完整词条含英音
        definitions = listOf(
            DictionaryDefinitionEntry(
                partOfSpeech = "noun",
                partOfSpeechOrder = 1,
                definitionOrder = 0,
                meaningEN = "good luck in making unexpected and fortunate discoveries",
                meaningCN = "偶然发现珍宝的运气",
                examples = listOf(
                    // 词典例句词（TATOEBA）：v6 起每次 lookup 走幂等重归位 diff（咨询词典源但不重复导入）
                    DictionaryExample(
                        sentence = "Finding this beach was pure serendipity.",
                        chineseTranslation = "找到这片海滩纯属意外之喜。",
                        sourceType = "TATOEBA",
                    ),
                ),
            ),
        ),
    )

    /** 非词典例句词（视频导入形态）：音标齐全 + 无词典例句 → 不触发回填，DB 命中不打扰词典源。 */
    private val videoExampledWord = DictionaryWord(
        text = "legion",
        ipaAm = "ˈliːdʒən",
        ipaBr = "ˈliːdʒən",
        definitions = listOf(
            DictionaryDefinitionEntry(
                partOfSpeech = "noun",
                partOfSpeechOrder = 1,
                definitionOrder = 0,
                meaningEN = "a vast host or number",
                meaningCN = "大批，众多",
                examples = listOf(
                    DictionaryExample(
                        sentence = "Their followers were legion.",
                        chineseTranslation = "他们的追随者多如牛毛。",
                        sourceType = "REAL_MOVIE_TV",
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
        // v6 重归位门：词典例句词二次查询也咨询词典源（幂等 diff），但绝不重复导入
        assertEquals(2, provider.calls)
        assertEquals(1L, db.database.wordQueries.countAll().executeAsOne())
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
        // 非词典例句词（视频导入形态）音标齐全 → 回填门短路，DB 命中不打扰词典源；
        // 词典例句词的幂等重归位 diff 由 SeedImporterTest 覆盖（SCR-SENSEATTR v6）
        importer.import(listOf(videoExampledWord))
        val repo = SqlDelightWordRepository(
            db.database,
            FakeProvider(explodeOnCall = true),
            importer,
            DispatchersForTest,
        )

        val detail = repo.lookup("legion")
        assertNotNull(detail)
        assertTrue(detail.entries.isNotEmpty())
    }
}
