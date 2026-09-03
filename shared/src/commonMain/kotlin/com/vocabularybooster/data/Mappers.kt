package com.vocabularybooster.data

import com.vocabularybooster.db.Example as ExampleRow
import com.vocabularybooster.db.SelectEntryWordsForBook
import com.vocabularybooster.db.SelectWordBookSummaries
import com.vocabularybooster.db.Word as WordRow
import com.vocabularybooster.db.WordBook as WordBookRow
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBook
import com.vocabularybooster.domain.model.WordBookType
import com.vocabularybooster.domain.model.WordBookWord

/**
 * db 行 → 领域模型映射（DOMAIN_MODEL 实体一一对应）。
 * 生成的行类型与领域类同名，故 import 别名 Row 后缀；
 * 排序键（Int 语义）由 INTEGER 列的 Long 显式转换。
 */

internal fun WordRow.toDomain(): Word = Word(
    wordId = wordId,
    text = text,
    normalizedText = normalizedText,
    ipaAm = ipaAm,
    ipaBr = ipaBr,
    pronunciationAudioUri = pronunciationAudioUri,
)

internal fun com.vocabularybooster.db.DefinitionEntry.toDomain() =
    com.vocabularybooster.domain.model.DefinitionEntry(
        definitionEntryId = definitionEntryId,
        wordId = wordId,
        partOfSpeech = partOfSpeech,
        partOfSpeechOrder = partOfSpeechOrder.toInt(),
        definitionOrder = definitionOrder.toInt(),
        meaningEN = meaningEN,
        meaningCN = meaningCN,
    )

internal fun ExampleRow.toDomain(): Example = Example(
    exampleId = exampleId,
    definitionEntryId = definitionEntryId,
    sentence = sentence,
    chineseTranslation = chineseTranslation,
    sourceType = runCatching { ExampleSourceType.valueOf(sourceType) }
        .getOrElse { error("Unknown ExampleSourceType in DB: $sourceType") },
    sourceRef = sourceRef,
    licenseNote = licenseNote,
    audioUri = audioUri,
    audioDurationMs = audioDurationMs,
    exampleOrder = exampleOrder.toInt(),
)

internal fun WordBookRow.toDomain(): WordBook = WordBook(
    wordBookId = wordBookId,
    type = if (type == "DERIVED") WordBookType.DERIVED else WordBookType.ORIGINAL,
    name = name,
    description = description,
    parentWordBookId = parentWordBookId,
    sourceSessionId = sourceSessionId,
)

internal fun SelectWordBookSummaries.toSummary() =
    com.vocabularybooster.domain.model.WordBookSummary(
        wordBook = WordBook(
            wordBookId = wordBookId,
            type = if (type == "DERIVED") WordBookType.DERIVED else WordBookType.ORIGINAL,
            name = name,
            description = description,
            parentWordBookId = parentWordBookId,
            sourceSessionId = sourceSessionId,
        ),
        entryCount = entryCount.toInt(),
    )

internal fun SelectEntryWordsForBook.toDomain(): WordBookWord = WordBookWord(
    wordBookEntryId = wordBookEntryId,
    wordId = wordId,
    wordText = wordText,
    entryOrder = entryOrder.toInt(),
    pendingTranslation = pendingTranslation,
)
