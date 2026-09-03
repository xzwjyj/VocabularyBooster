package com.vocabularybooster.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.domain.repository.WordBookRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * WordBookRepository 的 SQLDelight 实现（Phase 2，FR-4/FR-5）。
 * 删除守卫、保存校验、事务原子性全部在此层（业务规则只在 shared，架构铁律 2）。
 */
public class SqlDelightWordBookRepository(
    private val database: VocabularyDatabase,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WordBookRepository {

    override fun observeWordBooks(): Flow<List<WordBookSummary>> =
        database.queriesQueries.selectWordBookSummaries()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toSummary() } }

    override suspend fun getWordBooks(): List<WordBookSummary> = withContext(dispatcher) {
        database.queriesQueries.selectWordBookSummaries().executeAsList().map { it.toSummary() }
    }

    override suspend fun createWordBook(name: String): Long = withContext(dispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw RepositoryValidationException("生词本名称不能为空")
        }
        val now = clock.now().toEpochMilliseconds()
        // insert + last_insert_rowid 必须同事务同连接（JDBC 文件驱动 ThreadedConnectionManager
        // 每操作换连接，跨语句读 rowid 恒为 0；事务内钉住连接，Android 单连接亦不受影响）
        database.transactionWithResult {
            database.wordBookQueries.insertOriginalWordBook(trimmed, null, now, now)
            database.wordBookQueries.selectLastInsertRowId().executeAsOne()
        }
    }

    override suspend fun renameWordBook(wordBookId: Long, newName: String): Unit = withContext(dispatcher) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            throw RepositoryValidationException("生词本名称不能为空")
        }
        if (database.wordBookQueries.selectWordBookById(wordBookId).executeAsOneOrNull() == null) {
            throw RepositoryValidationException("生词本不存在：wordBookId=$wordBookId")
        }
        database.wordBookQueries.renameWordBook(trimmed, clock.now().toEpochMilliseconds(), wordBookId)
    }

    override suspend fun deleteWordBook(wordBookId: Long): Unit = withContext(dispatcher) {
        // FR-4 守卫：① 已获完成勋章（FR-13 永久性）② 存在派生子本（血缘完整）
        if (database.wordBookQueries.countBookCompletedMedals(wordBookId).executeAsOne() > 0L) {
            throw WordBookDeletionException(wordBookId, WordBookDeletionException.Reason.BOOK_HAS_COMPLETION_MEDAL)
        }
        if (database.wordBookQueries.countDerivedChildren(wordBookId).executeAsOne() > 0L) {
            throw WordBookDeletionException(wordBookId, WordBookDeletionException.Reason.HAS_DERIVED_CHILDREN)
        }
        // 只删关系数据（entries/selections/mastery 级联），底层 Word 不动（DOMAIN_MODEL §7）
        database.wordBookQueries.deleteWordBook(wordBookId)
    }

    override suspend fun getWordBookWords(wordBookId: Long): List<WordBookWord> = withContext(dispatcher) {
        database.wordBookEntryQueries.selectEntryWordsForBook(wordBookId).executeAsList().map { it.toDomain() }
    }

    override suspend fun removeWordFromWordBook(wordBookId: Long, wordId: Long): Unit = withContext(dispatcher) {
        database.transactionWithResult {
            database.wordBookEntryQueries.deleteEntryByWord(wordBookId, wordId)
            // 同步清掉本内掌握行，避免悬挂的 (bookId, wordId) 记录
            database.wordMasteryQueries.deleteMasteryForWordInBook(wordBookId, wordId)
        }
    }

    override suspend fun saveWordToBooks(request: SaveWordRequest): Unit = withContext(dispatcher) {
        if (request.wordBookIds.isEmpty()) {
            throw RepositoryValidationException("未选择生词本")
        }
        if (request.selections.isEmpty()) {
            throw RepositoryValidationException("未选择释义（至少一条）")
        }
        database.transactionWithResult {
            SaveRequestValidator(database).validate(request)
            val now = clock.now().toEpochMilliseconds()
            request.wordBookIds.forEach { bookId ->
                val existing = database.wordBookEntryQueries
                    .selectEntryByWord(bookId, request.wordId)
                    .executeAsOneOrNull()
                // 重存保留原 entryOrder（重存 = 替换选择，不是移动队尾）
                val entryOrder = existing?.entryOrder
                    ?: (database.wordBookEntryQueries
                        .maxEntryOrder(bookId) { max -> max ?: -1L }
                        .executeAsOne() + 1L)
                if (existing != null) {
                    database.wordBookEntryQueries.deleteEntryByWord(bookId, request.wordId) // 级联清旧选择
                }
                database.wordBookEntryQueries.insertEntry(
                    wordBookId = bookId,
                    wordId = request.wordId,
                    entryOrder = entryOrder,
                    pendingTranslation = existing?.pendingTranslation,
                    addedAt = now,
                )
                val newEntryId = database.wordBookEntryQueries.selectLastInsertRowId().executeAsOne()
                request.selections.forEach { selection ->
                    database.wordBookEntryDefinitionQueries.insertEntryDefinition(
                        wordBookEntryId = newEntryId,
                        definitionEntryId = selection.definitionEntryId,
                    )
                    selection.exampleIds.forEach { exampleId ->
                        database.wordBookEntryExampleSelectionQueries.insertExampleSelection(
                            wordBookEntryId = newEntryId,
                            exampleId = exampleId,
                        )
                    }
                }
            }
        }
    }
}
