package com.vocabularybooster.data.seed

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.db.DefinitionEntry as DefinitionEntryRow
import com.vocabularybooster.db.Example as ExampleRow
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
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
     * 增强回填（Phase 8.6 Tatoeba/音标 + SCR-SENSEATTR 例句重归位）：为已导入词条
     * 补齐例句/译文/音标并把例句按词典归属重新挂位。幂等、全量单事务；
     * 词不存在 → 0（绝不建词/建空行，[import] 才负责建词）。
     * 规则：音标只补空缺（ipaAm/ipaBr 为 null 且词典有值才写，绝不覆盖已有值）；
     * 例句 diff 重归位见 [reattributeExamples]。
     *
     * @return 变更行数（音标 + 例句插入/移动/译文/删除），0 = 无事可做
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
            if (entries.isEmpty()) return@transactionWithResult changed
            changed + reattributeExamples(word, entries)
        }
    }

    /**
     * 例句 diff 重归位（SCR-SENSEATTR，[backfillEnhancements] 内步骤，同事务内执行）。
     * 词典例句自 v6 起逐释义归属（DictionaryDefinitionEntry.examples 按释义分组）。
     * 释义 → DB 行映射按 Q1 位序（双方各自 (partOfSpeechOrder, definitionOrder) 排序后
     * 对位——不信任词条构建序，provider 之外的构造者无此契约）：
     * - **对齐词条**（DB 释义数 == 词典释义数——查词导入词）：缺句 → 插到对应释义
     *   （[applyExampleDiff]）；DB 有而词典已剔除的词典例句（短语改挂类）→
     *   **无勾选行引用才删**（[deleteStaleDictionaryExamples]，宁留不错删）。
     * - **非对齐词条**（TXT/视频导入词，释义结构与词典不一致）：defIdx 无法可靠映射 →
     *   保持旧语义（缺句挂首释义、只补译文差异，不移动不删除）。
     * 幂等：对齐后二次执行零写入。
     */
    private fun reattributeExamples(
        word: DictionaryWord,
        entries: List<DefinitionEntryRow>,
    ): Int {
        val aligned = word.definitions.size == entries.size
        // Q1 位序映射：entries 已按 Q1（仓储契约），词条侧显式排序后对位（构建序无关）
        val defsInQ1 = word.definitions.sortedWith(
            compareBy({ it.partOfSpeechOrder }, { it.definitionOrder }),
        )
        val perEntryOrder = mutableMapOf<Long, Long>() // definitionEntryId -> 下一个可用 exampleOrder
        val dbExamples = mutableListOf<ExampleRow>()
        for (entry in entries) {
            val list = database.exampleQueries
                .selectExamplesForEntry(entry.definitionEntryId)
                .executeAsList()
            dbExamples += list
            perEntryOrder[entry.definitionEntryId] = (list.maxOfOrNull { it.exampleOrder } ?: -1L) + 1L
        }
        val bySentence = HashMap<String, ExampleRow>()
        for (row in dbExamples) bySentence.putIfAbsent(row.sentence, row)
        val dictSentences = HashSet<String>()
        defsInQ1.forEach { definition ->
            definition.examples.forEach { dictSentences += it.sentence }
        }

        var changed = applyExampleDiff(aligned, defsInQ1, entries, bySentence, perEntryOrder)
        if (aligned) changed += deleteStaleDictionaryExamples(dbExamples, dictSentences)
        return changed
    }

    /**
     * 逐句 diff（仅对齐词条做移动；非对齐一律挂首释义）：缺句 → 插入目标释义；
     * 已有句归属不同 → [Example.sq] moveExampleToEntry 移动（exampleId 不变，用户勾选行
     * 经 exampleId 关联天然保留）；同位句 → 译文覆盖（仅词典来源行，绝不碰视频/种子例句）。
     */
    private fun applyExampleDiff(
        aligned: Boolean,
        defsInQ1: List<DictionaryDefinitionEntry>,
        entries: List<DefinitionEntryRow>,
        bySentence: Map<String, ExampleRow>,
        perEntryOrder: MutableMap<Long, Long>,
    ): Int {
        val firstEntry = entries.first()

        fun nextOrder(entryId: Long): Long {
            val next = perEntryOrder.getOrPut(entryId) { 0L }
            perEntryOrder[entryId] = next + 1L
            return next
        }

        var changed = 0
        defsInQ1.forEachIndexed { defIdx, definition ->
            val target = if (aligned) entries[defIdx] else firstEntry
            for (example in definition.examples) {
                val existing = bySentence[example.sentence]
                when {
                    existing == null -> {
                        database.exampleQueries.insertExample(
                            definitionEntryId = target.definitionEntryId,
                            sentence = example.sentence,
                            chineseTranslation = example.chineseTranslation,
                            sourceType = example.sourceType,
                            sourceRef = example.sourceRef,
                            licenseNote = example.licenseNote,
                            audioUri = example.audioUri,
                            audioDurationMs = example.audioDurationMs,
                            exampleOrder = nextOrder(target.definitionEntryId),
                        )
                        changed++
                    }
                    aligned && existing.definitionEntryId != target.definitionEntryId -> {
                        database.exampleQueries.moveExampleToEntry(
                            definitionEntryId = target.definitionEntryId,
                            exampleOrder = nextOrder(target.definitionEntryId),
                            exampleId = existing.exampleId,
                        )
                        changed++
                    }
                    else -> {
                        val overwrite = existing.isDictionarySourced() &&
                            example.chineseTranslation.isNotBlank() &&
                            existing.chineseTranslation != example.chineseTranslation
                        if (overwrite) {
                            database.exampleQueries.updateExampleTranslation(
                                chineseTranslation = example.chineseTranslation,
                                exampleId = existing.exampleId,
                            )
                            changed++
                        }
                    }
                }
            }
        }
        return changed
    }

    /**
     * 剔除分支（仅对齐词条）：词典已无此句的**词典来源**例句删除——非词典来源（视频/种子）
     * 永不删；被勾选行引用（countExampleSelections > 0）保留不删（宁留不错删，FR-5）。
     */
    private fun deleteStaleDictionaryExamples(
        dbExamples: List<ExampleRow>,
        dictSentences: Set<String>,
    ): Int {
        var changed = 0
        for (row in dbExamples) {
            val staleDictionaryRow = row.sentence !in dictSentences && row.isDictionarySourced()
            if (staleDictionaryRow) {
                val selections = database.exampleQueries
                    .countExampleSelections(row.exampleId)
                    .executeAsOne()
                if (selections == 0L) {
                    database.exampleQueries.deleteExampleById(row.exampleId)
                    changed++
                }
            }
        }
        return changed
    }

    /** 词典管线产出（TATOEBA/AI_GENERATED）——译文覆盖与剔除分支只作用于这类行。 */
    private fun ExampleRow.isDictionarySourced(): Boolean =
        sourceType == "TATOEBA" || sourceType == "AI_GENERATED"
}
