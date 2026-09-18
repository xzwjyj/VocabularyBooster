package com.vocabularybooster.data

import com.vocabularybooster.db.Achievement as AchievementRow
import com.vocabularybooster.db.Example as ExampleRow
import com.vocabularybooster.db.LearningSession as LearningSessionRow
import com.vocabularybooster.db.SelectEntryWordsForBook
import com.vocabularybooster.db.SelectWordBookSummaries
import com.vocabularybooster.db.SessionWord as SessionWordRow
import com.vocabularybooster.db.Word as WordRow
import com.vocabularybooster.db.WordBook as WordBookRow
import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.AchievementType
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.model.Example
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBook
import com.vocabularybooster.domain.model.WordBookType
import com.vocabularybooster.domain.model.WordBookWord
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

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

// 枚举 TEXT 列映射：未知值 fail-fast（脏数据显式暴露，不静默吞掉）
internal fun LearningSessionRow.toDomain(): LearningSession = LearningSession(
    sessionId = sessionId,
    wordBookId = wordBookId,
    status = runCatching { SessionStatus.valueOf(status) }
        .getOrElse { error("Unknown SessionStatus in DB: $status") },
    groupSize = groupSize.toInt(),
    startedAt = Instant.fromEpochMilliseconds(startedAt),
    endedAt = endedAt?.let(Instant::fromEpochMilliseconds),
)

internal fun SessionWordRow.toDomain(): SessionWord = SessionWord(
    sessionId = sessionId,
    wordId = wordId,
    groupIndex = groupIndex.toInt(),
    orderInGroup = orderInGroup.toInt(),
    status = runCatching { SessionWordStatus.valueOf(status) }
        .getOrElse { error("Unknown SessionWordStatus in DB: $status") },
    masteredAt = masteredAt?.let(Instant::fromEpochMilliseconds),
)

// —— Achievement（Phase 6，FR-13）：payloadJson 编解码 + 行映射 ——

// 与位置仓储同配置：未知字段忽略（前向兼容）
private val achievementJson = Json { ignoreUnknownKeys = true }

internal fun BookCompletedPayload.toJsonString(): String = achievementJson.encodeToString(this)

/** type/payload 未知值 fail-fast（脏数据显式暴露，同枚举列约定）。 */
internal fun AchievementRow.toDomain(): Achievement = Achievement(
    achievementId = achievementId,
    type = runCatching { AchievementType.valueOf(type) }
        .getOrElse { error("Unknown AchievementType in DB: $type") },
    wordBookId = wordBookId,
    payload = achievementJson.decodeFromString<BookCompletedPayload>(payloadJson),
    earnedAt = Instant.fromEpochMilliseconds(earnedAt),
)
