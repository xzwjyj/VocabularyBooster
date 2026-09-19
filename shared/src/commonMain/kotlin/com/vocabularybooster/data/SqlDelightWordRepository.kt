package com.vocabularybooster.data

import com.vocabularybooster.data.dictionary.FallbackDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.dictionary.DictionaryProvider
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
 * 两个依赖可空：缺省（纯 DB 模式，测试/手工 Koin 图）行为与 Phase 2 完全一致。
 */
public class SqlDelightWordRepository(
    private val database: VocabularyDatabase,
    private val dictionaryProvider: DictionaryProvider? = null,
    private val seedImporter: SeedImporter? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WordRepository {

    override suspend fun lookup(text: String): WordDetail? = withContext(dispatcher) {
        readFromDb(text) ?: importOnDemand(text)?.let { readFromDb(text) }
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
