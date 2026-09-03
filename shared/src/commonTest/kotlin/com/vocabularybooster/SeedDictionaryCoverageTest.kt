package com.vocabularybooster

import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 种子词库覆盖契约（Phase 2 DoD 数据要求；TEST_PLAN §9 fixtures 同源）。 */
class SeedDictionaryCoverageTest {

    private val words = SeedDictionaryProvider(SEED_DICTIONARY_JSON).loadAll()

    private val allowedPos = setOf(
        "verb", "noun", "adjective", "adverb", "pronoun",
        "preposition", "conjunction", "interjection", "determiner", "numeral", "phrase",
    )

    @Test
    fun seedMeetsPhase2CoverageContract() {
        // ≥50 词且 normalizedText 唯一
        assertTrue(words.size >= 50, "expected >= 50 words, got ${words.size}")
        val normalized = words.map { it.text.toNormalizedWordText() }
        assertEquals(normalized.size, normalized.toSet().size)

        val allDefinitions = words.flatMap { it.definitions }

        // 多词性 / 同词性多释义 / 多例句 覆盖
        val multiPosWords = words.count { w ->
            w.definitions.map { it.partOfSpeech }.distinct().size >= 2
        }
        val multiDefSamePos = words.sumOf { w ->
            w.definitions.groupBy { it.partOfSpeech }.count { (_, defs) -> defs.size >= 2 }
        }
        val entriesWithMultipleExamples = allDefinitions.count { it.examples.size >= 2 }
        assertTrue(multiPosWords >= 5, "expected >= 5 multi-POS words, got $multiPosWords")
        assertTrue(multiDefSamePos >= 10, "expected >= 10 same-POS multi-def groups, got $multiDefSamePos")
        assertTrue(
            entriesWithMultipleExamples >= 10,
            "expected >= 10 entries with 2+ examples, got $entriesWithMultipleExamples",
        )

        // 每条释义至少 1 例句；词性与文本合法
        allDefinitions.forEach { def ->
            assertTrue(
                def.examples.isNotEmpty(),
                "definition with no examples: ${def.partOfSpeech}#${def.definitionOrder}",
            )
            assertTrue(def.partOfSpeech in allowedPos, "unknown POS: ${def.partOfSpeech}")
            assertTrue(def.partOfSpeechOrder >= 0)
            assertTrue(def.definitionOrder >= 1)
            def.examples.forEach { example ->
                assertTrue(example.sentence.isNotBlank())
                assertTrue(example.chineseTranslation.isNotBlank())
            }
        }

        // 多来源：TTS + LICENSED_OTHER（公版；必须带 sourceRef + licenseNote）
        val sourceTypes = allDefinitions.flatMap { it.examples }.map { it.sourceType }.toSet()
        assertTrue("TTS" in sourceTypes)
        assertTrue("LICENSED_OTHER" in sourceTypes)
        allDefinitions.flatMap { it.examples }
            .filter { it.sourceType == "LICENSED_OTHER" }
            .forEach { example ->
                assertTrue(!example.sourceRef.isNullOrBlank(), "LICENSED_OTHER missing sourceRef")
                assertTrue(!example.licenseNote.isNullOrBlank(), "LICENSED_OTHER missing licenseNote")
            }
    }

    @Test
    fun providerLookupIsCaseInsensitiveAndMissesReturnNull() = runTest {
        val provider = SeedDictionaryProvider(SEED_DICTIONARY_JSON)
        assertEquals("boost", provider.lookup("BOOST")?.text)
        assertEquals("boost", provider.lookup("  boost ")?.text)
        assertTrue(provider.lookup("zzzz-not-a-word") == null)
    }
}
