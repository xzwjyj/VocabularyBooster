package com.vocabularybooster.data

import com.vocabularybooster.data.dictionary.FallbackDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.model.toNormalizedWordText
import com.vocabularybooster.domain.repository.WordRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WordRepository 的 SQLDelight 实现（Phase 2）。
 * 派发器注入（NFR-9）；排序恒走 Q1（架构铁律 3）。
 * Phase 8.6（FR-18）按需导入：DB miss 且词典源（[FallbackDictionaryProvider] 复合：
 * 种子优先 → 随包全量兜底）命中时，经 [SeedImporter] 导入该单词（幂等、单事务，
 * 与种子同代码路径）后重读返回——查词覆盖全量词典，库只长不缩。
 * 增强回填：增强前已导入的词条在 lookup 时经 [SeedImporter.backfillEnhancements]
 * 补齐例句/译文/音标（幂等、单事务）后重读。
 * 两个依赖可空：缺省（纯 DB 模式，测试/手工 Koin 图）行为与 Phase 2 完全一致。
 */
public class SqlDelightWordRepository(
    private val database: VocabularyDatabase,
    private val dictionaryProvider: DictionaryProvider? = null,
    private val seedImporter: SeedImporter? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WordRepository {

    override suspend fun lookup(text: String): WordDetail? = withContext(dispatcher) {
        val fromDb = readFromDb(text)
        when {
            fromDb == null -> importOnDemand(text)?.let { readFromDb(text) }
            needsEnhancementBackfill(fromDb) -> backfillEnhancements(text, fromDb)
            else -> fromDb
        }
    }

    /**
     * FR-18 增强回填：旧导入词条（例句增强前无例句、Tatoeba 译文缺失、或音标缺失）→
     * 从词典源补齐后重读。词典源未收录/无可补 → 原样返回（每次 lookup 多一次
     * 本地词典查询，~ms 级，可接受）。
     */
    private suspend fun backfillEnhancements(text: String, current: WordDetail): WordDetail {
        val provider = dictionaryProvider
        val importer = seedImporter
        val dictWord = if (provider != null && importer != null) provider.lookup(text) else null
        val changed = dictWord
            ?.takeIf { word ->
                word.definitions.sumOf { it.examples.size } > 0 ||
                    !word.ipaAm.isNullOrBlank() ||
                    !word.ipaBr.isNullOrBlank()
            }
            ?.let { word -> importer?.backfillEnhancements(word) ?: 0 }
            ?: 0
        return if (changed > 0) readFromDb(text) ?: current else current
    }

    /** 美音/英音音标缺失、零例句，或 Tatoeba 例句存在但译文为空（旧单串格式资产导入）。 */
    private fun needsEnhancementBackfill(detail: WordDetail): Boolean {
        val examples = detail.examplesByEntryId.values.flatten()
        val examplesIncomplete = examples.isEmpty() ||
            examples.any {
                it.sourceType == ExampleSourceType.TATOEBA && it.chineseTranslation.isBlank()
            }
        return detail.word.ipaAm.isNullOrBlank() ||
            detail.word.ipaBr.isNullOrBlank() ||
            examplesIncomplete
    }

    /** FR-18：miss → 源命中 → 导入 → true；未装配词典或源未收录返回 false。 */
    private suspend fun importOnDemand(text: String): Boolean {
        val provider = dictionaryProvider ?: return false
        val word = provider.lookup(text)
        return if (word == null || seedImporter == null) {
            false
        } else {
            seedImporter.import(listOf(word))
            true
        }
    }

    private fun readFromDb(text: String): WordDetail? {
        val row = database.wordQueries
            .selectByNormalizedText(text.toNormalizedWordText())
            .executeAsOneOrNull()
            ?: return null
        val entries = database.definitionEntryQueries
            .selectDefinitionsForWord(row.wordId)
            .executeAsList()
        val examplesByEntryId = entries.associate { entry ->
            val definitionEntryId = entry.definitionEntryId
            definitionEntryId to database.exampleQueries
                .selectExamplesForEntry(definitionEntryId)
                .executeAsList()
                .map { it.toDomain() }
        }
        return WordDetail(
            word = row.toDomain(),
            entries = entries.map { it.toDomain() },
            examplesByEntryId = examplesByEntryId,
        )
    }

    override suspend fun search(query: String, limit: Int): List<Word> = withContext(dispatcher) {
        val normalized = query.toNormalizedWordText()
        if (normalized.isEmpty()) {
            emptyList()
        } else {
            // DB 搜索
            val dbResults = database.wordQueries
                .searchWords(normalized + "%", limit.toLong())
                .executeAsList()
                .map { it.toDomain() }
                .toMutableList()
            // 如果 DB 结果不足 limit，尝试从词典补全（FR-18）
            val provider = dictionaryProvider
            if (dbResults.size < limit && provider != null) {
                val dictWord = provider.lookup(query)
                if (dictWord != null) {
                    // 检查是否已在 DB 结果中
                    val normalizedDict = dictWord.text.lowercase().trim()
                    if (dbResults.none { it.normalizedText == normalizedDict }) {
                        // 词典中有但 DB 没有 → 尝试按需导入
                        if (seedImporter != null) {
                            seedImporter.import(listOf(dictWord))
                            // 重新从 DB 读取
                            val imported = database.wordQueries
                                .selectByNormalizedText(normalizedDict)
                                .executeAsOneOrNull()
                            if (imported != null && dbResults.size < limit) {
                                dbResults.add(imported.toDomain())
                            }
                        }
                    }
                }
            }
            dbResults.take(limit)
        }
    }
}
