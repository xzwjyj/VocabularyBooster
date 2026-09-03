package com.vocabularybooster.domain.dictionary

import kotlinx.serialization.Serializable

/**
 * 词典数据源端口（PROJECT_SPEC FR-16）。
 * v1 唯一实现：本地种子 JSON（SeedDictionaryProvider）；
 * 未来第三方词典 API / AI 内容以新增实现接入，不改核心模型。
 * 所有来源必须如实填写 sourceType / sourceRef / licenseNote（NFR-5）。
 */
public interface DictionaryProvider {

    /** 查一个词的完整词条；未收录返回 null。大小写/首尾空白不敏感由实现负责。 */
    public suspend fun lookup(text: String): DictionaryWord?
}

/** 词典侧完整词条（provider 契约，@Serializable 以 JSON 种子为 v1 载体）。 */
@Serializable
public data class DictionaryWord(
    val text: String,
    val ipaAm: String? = null,
    val ipaBr: String? = null,
    val definitions: List<DictionaryDefinitionEntry> = emptyList(),
)

@Serializable
public data class DictionaryDefinitionEntry(
    val partOfSpeech: String,
    val partOfSpeechOrder: Int,
    val definitionOrder: Int,
    val meaningEN: String,
    val meaningCN: String,
    val examples: List<DictionaryExample> = emptyList(),
)

@Serializable
public data class DictionaryExample(
    val sentence: String,
    val chineseTranslation: String,
    val sourceType: String = "TTS",
    val sourceRef: String? = null,
    val licenseNote: String? = null,
    val audioUri: String? = null,
    val audioDurationMs: Long? = null,
    val exampleOrder: Int = 0,
)

/** 种子文件整体结构（version 预留后续种子演进）。 */
@Serializable
public data class SeedDictionary(
    val version: Int,
    val words: List<DictionaryWord> = emptyList(),
)
