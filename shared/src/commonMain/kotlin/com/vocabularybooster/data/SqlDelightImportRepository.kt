package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.importing.ImportRepository
import com.vocabularybooster.importing.ImportSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * ImportRepository 的 SQLDelight 实现（Phase 7，FR-14，IMPORT_SPEC §5）：
 * **单一大事务**承载全部写入——块内异常/协程取消 → 整体回滚，目标本保持导入前原样。
 *
 * SQLDelight 2.0.2 同步事务的 lambda 不挂起（事务线程钉死；挂起事务只随 async-driver
 * 架构提供，coroutines-extensions 仅含 FlowQuery），而引擎需在事务内流式消费行流——
 * 以 `runBlocking(父 Job)` 钉住当前 [dispatcher] 线程桥接：runBlocking 的事件循环
 * 替换拦截器、不发生线程跳转（满足 SQLite 事务线程亲和）；父 Job 外接使外层协程取消
 * 传导为 CancellationException → 事务整体回滚（引擎每 256 行 ensureActive，取消
 * ≤1s 生效，NFR-2）。事务内去重四层裁决在 ImportEngine（业务规则只在引擎），
 * 本类只实现 [ImportSession] 原子操作面（全部复用既有命名查询 + 补写译文一条新查询）。
 */
public class SqlDelightImportRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImportRepository {

    override suspend fun <T> withImportTransaction(block: suspend ImportSession.() -> T): T =
        withContext(dispatcher) {
            val outerJob = currentCoroutineContext().job
            database.transactionWithResult {
                runBlocking(outerJob) { block(Session()) }
            }
        }

    private inner class Session : ImportSession {

        override fun createWordBook(name: String, nowMs: Long): Long {
            database.wordBookQueries.insertOriginalWordBook(name, null, nowMs, nowMs)
            return database.wordBookQueries.selectLastInsertRowId().executeAsOne()
        }

        override fun findWordIdByNormalizedText(normalizedText: String): Long? =
            database.wordQueries.selectByNormalizedText(normalizedText).executeAsOneOrNull()?.wordId

        override fun insertWord(text: String, normalizedText: String, nowMs: Long): Long {
            database.wordQueries.insertWord(text, normalizedText, null, null, null, nowMs, nowMs)
            return database.wordQueries.selectLastInsertRowId().executeAsOne()
        }

        override fun findEntry(wordBookId: Long, wordId: Long): ImportSession.ExistingEntry? =
            database.wordBookEntryQueries.selectEntryByWord(wordBookId, wordId)
                .executeAsOneOrNull()
                ?.let { ImportSession.ExistingEntry(it.pendingTranslation) }

        override fun maxEntryOrder(wordBookId: Long): Long? =
            database.wordBookEntryQueries.maxEntryOrder(wordBookId).executeAsOneOrNull()?.MAX

        override fun insertEntry(
            wordBookId: Long,
            wordId: Long,
            entryOrder: Long,
            pendingTranslation: String?,
            nowMs: Long,
        ) {
            database.wordBookEntryQueries.insertEntry(wordBookId, wordId, entryOrder, pendingTranslation, nowMs)
        }

        override fun updateEntryPendingTranslation(
            wordBookId: Long,
            wordId: Long,
            pendingTranslation: String,
        ) {
            database.wordBookEntryQueries
                .updateEntryPendingTranslation(pendingTranslation, wordBookId, wordId)
        }
    }
}
