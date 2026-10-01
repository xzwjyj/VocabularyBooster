package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
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
 * 本类只实现 [ImportSession] 原子操作面（复用既有命名查询；新查询仅补写译文 +
 * SCR-TXTDICTENRICH 富化的音标只补空缺一条，均 query-only 无迁移）。
 *
 * 富化说明（SCR-TXTDICTENRICH）：provider lookup 只读随包词典资产库，与
 * vocabulary.db 事务无锁交互；provider 内部 withContext(Dispatchers.IO) 在返回时
 * 恢复原（事务钉死）线程——SQLite 线程亲和不被破坏。
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

    @Suppress("TooManyFunctions") // 端口 12 个方法的 1:1 实现（见端口 suppress 说明）
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
                ?.let {
                    ImportSession.ExistingEntry(
                        pendingTranslation = it.pendingTranslation,
                        hasDefinitionSelections = database.wordBookEntryDefinitionQueries
                            .countEntryDefinitions(it.wordBookEntryId)
                            .executeAsOne() > 0L,
                    )
                }

        override fun maxEntryOrder(wordBookId: Long): Long? =
            database.wordBookEntryQueries.maxEntryOrder(wordBookId).executeAsOneOrNull()?.MAX

        override fun insertEntry(
            wordBookId: Long,
            wordId: Long,
            entryOrder: Long,
            pendingTranslation: String?,
            nowMs: Long,
        ): Long {
            database.wordBookEntryQueries.insertEntry(wordBookId, wordId, entryOrder, pendingTranslation, nowMs)
            return database.wordBookEntryQueries.selectLastInsertRowId().executeAsOne()
        }

        override fun updateEntryPendingTranslation(
            wordBookId: Long,
            wordId: Long,
            pendingTranslation: String,
        ) {
            database.wordBookEntryQueries
                .updateEntryPendingTranslation(pendingTranslation, wordBookId, wordId)
        }

        override fun findDefinitionsForWord(wordId: Long): List<ImportSession.DefinitionWithExamples> =
            database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList().map { row ->
                ImportSession.DefinitionWithExamples(
                    definitionEntryId = row.definitionEntryId,
                    partOfSpeech = row.partOfSpeech,
                    meaningEN = row.meaningEN,
                    meaningCN = row.meaningCN,
                    exampleIds = database.exampleQueries
                        .selectExamplesForEntry(row.definitionEntryId)
                        .executeAsList()
                        .map { it.exampleId },
                )
            }

        override fun importDefinitionWithExamples(
            wordId: Long,
            definition: DictionaryDefinitionEntry,
        ): ImportSession.DefinitionWithExamples {
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
            val exampleIds = definition.examples.map { example ->
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
                database.exampleQueries.selectLastInsertRowId().executeAsOne()
            }
            return ImportSession.DefinitionWithExamples(
                definitionEntryId = definitionEntryId,
                partOfSpeech = definition.partOfSpeech,
                meaningEN = definition.meaningEN,
                meaningCN = definition.meaningCN,
                exampleIds = exampleIds,
            )
        }

        override fun fillBlankPronunciation(wordId: Long, ipaAm: String?, ipaBr: String?, nowMs: Long) {
            database.wordQueries.fillBlankPronunciation(ipaAm, ipaBr, nowMs, wordId)
        }

        override fun insertEntryDefinitionSelection(wordBookEntryId: Long, definitionEntryId: Long) {
            database.wordBookEntryDefinitionQueries
                .insertEntryDefinition(wordBookEntryId, definitionEntryId)
        }

        override fun insertExampleSelection(wordBookEntryId: Long, exampleId: Long) {
            database.wordBookEntryExampleSelectionQueries
                .insertExampleSelection(wordBookEntryId, exampleId)
        }
    }
}
