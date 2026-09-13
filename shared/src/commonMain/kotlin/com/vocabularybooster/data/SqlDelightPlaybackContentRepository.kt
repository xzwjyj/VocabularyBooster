package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.db.Example as ExampleRow
import com.vocabularybooster.db.Word as WordRow
import com.vocabularybooster.domain.model.PlaybackContent
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PlaybackContentRepository 的 SQLDelight 实现（Phase 4 Step 1，AUDIO §2 / Q4 + Q4b）。
 * 单事务一致性读取（只读快照）：entry 存在性、Word 行、选中释义、选中例句取自同一时刻，
 * 不出现释义与例句跨时刻的可观察不一致；不引入写事务。
 * 排序全部由 Q4 `(partOfSpeechOrder, definitionOrder)` 与 Q4b `exampleOrder` 的
 * ORDER BY 保证，本层不重排；groupBy 保持遭遇顺序（LinkedHashMap 语义）。
 */
public class SqlDelightPlaybackContentRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackContentRepository {

    override suspend fun getPlaybackContent(
        wordBookId: Long,
        wordId: Long,
    ): PlaybackContent? = withContext(dispatcher) {
        database.transactionWithResult {
            val entry = database.wordBookEntryQueries.selectEntryByWord(wordBookId, wordId)
                .executeAsOneOrNull()
                ?: return@transactionWithResult null
            val word: WordRow = database.wordQueries.selectById(wordId).executeAsOneOrNull()
                ?: return@transactionWithResult null
            val definitions = database.queriesQueries.selectSelectedDefinitions(entry.wordBookEntryId)
                .executeAsList()
                .map { it.toDomain() }
            val examples: List<ExampleRow> = database.queriesQueries.selectSelectedExamples(entry.wordBookEntryId)
                .executeAsList()
            PlaybackContent(
                word = word.toDomain(),
                selectedDefinitions = definitions,
                examplesByDefinitionEntryId = examples.map { it.toDomain() }.groupBy { it.definitionEntryId },
            )
        }
    }
}
