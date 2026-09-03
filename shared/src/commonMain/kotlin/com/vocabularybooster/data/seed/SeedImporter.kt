package com.vocabularybooster.data.seed

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** 种子导入报告（新增 / 复用计数）。 */
public data class SeedImportReport(
    val insertedWords: Int,
    val reusedWords: Int,
)

/**
 * 种子导入器（Phase 2）：JSON 词库 → DB。
 * 幂等：normalizedText 已存在 → 复用跳过（绝不重复建词，FR-5 复用原则）；
 * 全量单事务（失败零残留）；时间走注入 Clock（铁律 10）。
 */
public class SeedImporter(
    private val database: VocabularyDatabase,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    public suspend fun import(words: List<DictionaryWord>): SeedImportReport = withContext(dispatcher) {
        var inserted = 0
        var reused = 0
        val now = clock.now().toEpochMilliseconds()
        database.transactionWithResult {
            for (word in words) {
                val normalized = word.text.toNormalizedWordText()
                val existing = database.wordQueries
                    .selectByNormalizedText(normalized)
                    .executeAsOneOrNull()
                if (existing != null) {
                    reused++
                    continue
                }
                database.wordQueries.insertWord(
                    text = word.text.trim(),
                    normalizedText = normalized,
                    ipaAm = word.ipaAm,
                    ipaBr = word.ipaBr,
                    pronunciationAudioUri = null,
                    createdAt = now,
                    updatedAt = now,
                )
                val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
                for (definition in word.definitions) {
                    database.definitionEntryQueries.insertDefinitionEntry(
                        wordId = wordId,
                        partOfSpeech = definition.partOfSpeech,
                        partOfSpeechOrder = definition.partOfSpeechOrder.toLong(),
                        definitionOrder = definition.definitionOrder.toLong(),
                        meaningEN = definition.meaningEN,
                        meaningCN = definition.meaningCN,
                    )
                    val definitionEntryId = database.definitionEntryQueries
                        .selectLastInsertRowId()
                        .executeAsOne()
                    for (example in definition.examples) {
                        database.exampleQueries.insertExample(
                            definitionEntryId = definitionEntryId,
                            sentence = example.sentence,
                            chineseTranslation = example.chineseTranslation,
                            sourceType = example.sourceType,
                            sourceRef = example.sourceRef,
                            licenseNote = example.licenseNote,
                            audioUri = example.audioUri,
                            audioDurationMs = example.audioDurationMs,
                            exampleOrder = example.exampleOrder.toLong(),
                        )
                    }
                }
                inserted++
            }
            SeedImportReport(insertedWords = inserted, reusedWords = reused)
        }
    }

    /** 首启装配：库为空才导入；已有词条返回 null（幂等）。 */
    public suspend fun ensureSeeded(provider: SeedDictionaryProvider): SeedImportReport? =
        withContext(dispatcher) {
            val count = database.wordQueries.countAll().executeAsOne()
            if (count > 0L) null else import(provider.loadAll())
        }
}
