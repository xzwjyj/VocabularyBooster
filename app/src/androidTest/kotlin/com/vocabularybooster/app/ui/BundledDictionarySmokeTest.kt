package com.vocabularybooster.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.platform.BundledDictionaryProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 随包全量词典冒烟（Phase 8.6，TC-UI 词典组；需模拟器 + 真实 asset）：
 * 1) BundledDictionaryProvider 直查——单词/短语/大小写归一（FR-18 源侧）；
 * 2) Koin 全链路——WordRepository DB miss → 复合源兜底 → 按需导入落库（FR-18 接线侧）。
 * 资产未打包（CI 未生成 ecdict.sqlite）时 provider 永久 null——assume 跳过不判失败。
 */
@RunWith(AndroidJUnit4::class)
class BundledDictionarySmokeTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledAssetServesWordAndPhrase() = runBlocking {
        val provider = BundledDictionaryProvider(targetContext)
        val word = provider.lookup("  Serendipity ")
        org.junit.Assume.assumeTrue("bundled dict asset absent", word != null)
        assertEquals("Serendipity", word!!.text)
        assertTrue(word.definitions.isNotEmpty())

        val phrase = provider.lookup("take off")
        assertNotNull(phrase)
        assertTrue(phrase!!.definitions.isNotEmpty())
    }

    @Test
    fun koinWiredLookupImportsOnDemandAndPersists() = runBlocking {
        val repo = GlobalContext.get().get<WordRepository>()
        val detail = repo.lookup("serendipity")
        org.junit.Assume.assumeTrue("bundled dict asset absent", detail != null)

        val db = GlobalContext.get().get<com.vocabularybooster.db.VocabularyDatabase>()
        assertNotNull(
            db.wordQueries.selectByNormalizedText("serendipity").executeAsOneOrNull(),
        )
    }
}
