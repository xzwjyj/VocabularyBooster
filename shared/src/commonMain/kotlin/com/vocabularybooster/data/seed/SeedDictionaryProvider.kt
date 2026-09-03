package com.vocabularybooster.data.seed

import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.dictionary.SeedDictionary
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.serialization.json.Json

/**
 * DictionaryProvider 的 v1 实现：本地种子 JSON（FR-16；TEST_PLAN §9 fixtures 同源）。
 * FR-16 可插拔验收：以桩替换本类无需改动任何引擎/仓储代码。
 */
public class SeedDictionaryProvider(
    seedJson: String,
) : DictionaryProvider {

    private val json: Json = Json { ignoreUnknownKeys = true }

    private val parsed: SeedDictionary by lazy {
        json.decodeFromString(SeedDictionary.serializer(), seedJson)
    }

    /** 种子全量词条（SeedImporter 首启导入用）。 */
    public fun loadAll(): List<DictionaryWord> = parsed.words

    override suspend fun lookup(text: String): DictionaryWord? {
        val normalized = text.toNormalizedWordText()
        return parsed.words.firstOrNull { it.text.toNormalizedWordText() == normalized }
    }
}
