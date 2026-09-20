package com.vocabularybooster.data.seed

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.db.DefinitionEntry as DefinitionEntryRow
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

    /**
     * 增强回填（Phase 8.6 Tatoeba/音标）：为增强前已导入的词条补齐例句/译文/音标。
     * 幂等、全量单事务；词不存在 → 0（绝不建词/建空行，[import] 才负责建词）。
     * 规则：音标只补空缺（ipaAm/ipaBr 为 null 且词典有值才写，绝不覆盖已有值）；
     * 同句已存在（跨释义按句子去重）→ 跳过；存在但译文空且词典有译文 → 只更新译文
     * （[Example.sq] updateExampleTranslation）；缺句 → 挂到首释义（与词典例句挂载约定一致）。
     *
     * @return 变更行数（更新音标 + 新增例句 + 更新译文），0 = 无事可做
     */
    public suspend fun backfillEnhancements(word: DictionaryWord): Int = withContext(dispatcher) {
        database.transactionWithResult {
            val normalized = word.text.toNormalizedWordText()
            val existingWord = database.wordQueries
                .selectByNormalizedText(normalized)
                .executeAsOneOrNull()
                ?: return@transactionWithResult 0

            var changed = 0
            // 音标回填（零释义词也适用——TXT 导入词无释义，见 FR-14）：只补空缺
            if (existingWord.ipaAm == null && !word.ipaAm.isNullOrBlank()) {
                database.wordQueries.updateWordIpa(
                    ipaAm = word.ipaAm,
                    updatedAt = clock.now().toEpochMilliseconds(),
                    wordId = existingWord.wordId,
                )
                changed++
            }
            // 英音回填（FR-22，同「只补空缺」口径——ipaBr 为 null 且词典有值才写）
            if (existingWord.ipaBr == null && !word.ipaBr.isNullOrBlank()) {
                database.wordQueries.updateWordIpaBr(
                    ipaBr = word.ipaBr,
                    updatedAt = clock.now().toEpochMilliseconds(),
                    wordId = existingWord.wordId,
                )
                changed++
            }
            val entries = database.definitionEntryQueries
                .selectDefinitionsForWord(existingWord.wordId)
                .executeAsList()
            val firstEntry = entries.firstOrNull()
                ?: return@transactionWithResult changed
            changed + backfillMissingExamples(word, entries, firstEntry)
        }
    }

    /**
     * 例句/译文回填主体（[backfillEnhancements] 内步骤，同事务内执行）：
     * 全词现有例句索引（句子 → 行，跨释义去重）；同句已存在 → 跳过；
     * 存在但译文空且词典有译文 → 只更新译文；缺句 → 挂到首释义（与词典例句挂载约定一致）。
     */
    private fun backfillMissingExamples(
        word: DictionaryWord,
        entries: List<DefinitionEntryRow>,
        firstEntry: DefinitionEntryRow,
    ): Int {
        val bySentence = buildMap {
            for (entry in entries) {
                database.exampleQueries
                    .selectExamplesForEntry(entry.definitionEntryId)
                    .executeAsList()
                    .forEach { putIfAbsent(it.sentence, it) }
            }
        }
        val firstEntryMaxOrder = database.exampleQueries
            .selectExamplesForEntry(firstEntry.definitionEntryId)
            .executeAsList()
            .maxOfOrNull { it.exampleOrder }

        var changed = 0
        var nextOrder = (firstEntryMaxOrder?.plus(1)) ?: 0L
        for (definition in word.definitions) {
            for (example in definition.examples) {
                val existing = bySentence[example.sentence]
                when {
                    existing == null -> {
                        database.exampleQueries.insertExample(
                            definitionEntryId = firstEntry.definitionEntryId,
                            sentence = example.sentence,
                            chineseTranslation = example.chineseTranslation,
                            sourceType = example.sourceType,
                            sourceRef = example.sourceRef,
                            licenseNote = example.licenseNote,
                            audioUri = example.audioUri,
                            audioDurationMs = example.audioDurationMs,
                            exampleOrder = nextOrder,
                        )
                        nextOrder++
                        changed++
                    }
                    existing.chineseTranslation.isBlank() && example.chineseTranslation.isNotBlank() -> {
                        database.exampleQueries.updateExampleTranslation(
                            chineseTranslation = example.chineseTranslation,
                            exampleId = existing.exampleId,
                        )
                        changed++
                    }
                }
            }
        }
        return changed
    }
}
