package com.vocabularybooster.domain.model

/**
 * Word 聚合领域模型（DOMAIN_MODEL §2.1–§2.3）。
 * 与 SQLDelight 生成行类型解耦：db 行 → 领域映射见 data/Mappers.kt。
 */

/** 单词母数据（全局唯一、共享只读；normalizedText 全局唯一）。 */
public data class Word(
    val wordId: Long,
    val text: String,
    val normalizedText: String,
    val ipaAm: String? = null,
    val ipaBr: String? = null,
    val pronunciationAudioUri: String? = null,
)

/** 释义条目：MeaningEN / MeaningCN 同属一条，任何层不得拆分（FR-2 / I-6）。 */
public data class DefinitionEntry(
    val definitionEntryId: Long,
    val wordId: Long,
    val partOfSpeech: String,
    val partOfSpeechOrder: Int,
    val definitionOrder: Int,
    val meaningEN: String,
    val meaningCN: String,
)

/** 例句来源类型（DOMAIN_MODEL §3.2 / FR-3）。 */
public enum class ExampleSourceType {
    REAL_MOVIE_TV,
    CELEBRITY_SPEECH,
    TED,
    AUDIOBOOK,
    LICENSED_OTHER,
    TTS,
    /** Tatoeba 语料库例句（CC-BY 2.0 FR，Phase 8.6 全量词典例句增强）。 */
    TATOEBA,
}

/** 例句原子单元：句 + 译文 + 音频元数据 + 来源合规信息（FR-3）。 */
public data class Example(
    val exampleId: Long,
    val definitionEntryId: Long,
    val sentence: String,
    val chineseTranslation: String,
    val sourceType: ExampleSourceType,
    val sourceRef: String? = null,
    val licenseNote: String? = null,
    val audioUri: String? = null,
    val audioDurationMs: Long? = null,
    val exampleOrder: Int = 0,
)

/** 词条详情聚合：entries 已按 (partOfSpeechOrder, definitionOrder) 排序（Q1，FR-2）。 */
public data class WordDetail(
    val word: Word,
    val entries: List<DefinitionEntry>,
    val examplesByEntryId: Map<Long, List<Example>>,
)

/** FR-2 连续分组：同词性的 DefinitionEntry 连续出现后进入下一词性（I-1/I-2）。 */
public fun WordDetail.groupedByPartOfSpeech(): List<PartOfSpeechGroup> {
    val builders = mutableListOf<Pair<String, MutableList<DefinitionWithExamples>>>()
    for (entry in entries) {
        val withExamples = DefinitionWithExamples(entry, examplesByEntryId[entry.definitionEntryId].orEmpty())
        val last = builders.lastOrNull()
        if (last != null && last.first == entry.partOfSpeech) {
            last.second += withExamples
        } else {
            builders += entry.partOfSpeech to mutableListOf(withExamples)
        }
    }
    return builders.map { PartOfSpeechGroup(it.first, it.second) }
}

/** 词性组（组内已按 definitionOrder 升序）。 */
public data class PartOfSpeechGroup(
    val partOfSpeech: String,
    val definitions: List<DefinitionWithExamples>,
)

/** 释义 + 其例句（渲染与播放共用结构，I-4）。 */
public data class DefinitionWithExamples(
    val entry: DefinitionEntry,
    val examples: List<Example>,
)
