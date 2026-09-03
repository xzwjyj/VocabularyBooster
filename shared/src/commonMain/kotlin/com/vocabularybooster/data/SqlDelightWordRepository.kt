package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
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
 */
public class SqlDelightWordRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WordRepository {

    override suspend fun lookup(text: String): WordDetail? = withContext(dispatcher) {
        val row = database.wordQueries
            .selectByNormalizedText(text.toNormalizedWordText())
            .executeAsOneOrNull()
            ?: return@withContext null
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
        WordDetail(
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
            database.wordQueries
                .searchWords(normalized + "%", limit.toLong())
                .executeAsList()
                .map { it.toDomain() }
        }
    }
}
