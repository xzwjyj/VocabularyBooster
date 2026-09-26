package com.vocabularybooster.data.videoimport

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * 视频导入引擎
 *
 * 将 video_import/data.json 数据包导入到数据库
 *
 * 流程：
 * 1. 创建或复用生词本（按 wordBookName）
 * 2. 对每个单词：复用已有 Word 或新建
 * 3. 对每个释义：新建 DefinitionEntry
 * 4. 对每个例句：新建 Example（带音频 URI）
 * 5. 创建 WordBookEntry 关联
 * 6. 默认全选释义和例句（视频语境完整保留）
 */
public class VideoImportEngine(
    private val database: VocabularyDatabase,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 导入数据包
     *
     * @param pkg 视频导入数据包
     * @return 导入报告
     */
    public suspend fun import(pkg: VideoImportPackage): VideoImportReport = withContext(dispatcher) {
        var importedWords = 0
        var reusedWords = 0
        var importedExamples = 0
        val now = clock.now().toEpochMilliseconds()

        database.transactionWithResult {
            // 1. 创建或查找生词本
            val wordBookId = findOrCreateWordBook(pkg.wordBookName, now)
            var nextOrder = nextEntryOrder(wordBookId)

            // 2. 处理每个词条
            for (entry in pkg.entries) {
                val (wordId, createdNew) = findOrCreateWord(entry, now)
                if (createdNew) importedWords++ else reusedWords++

                // 词已在生词本中，跳过
                if (isEntryInWordBook(wordBookId, wordId)) continue

                importedExamples += importEntry(
                    entry, wordId,
                    EntryContext(wordBookId, pkg.sourceVideo, nextOrder, now),
                )
                nextOrder++
            }

            VideoImportReport(
                wordBookName = pkg.wordBookName,
                totalWords = pkg.entries.size,
                importedWords = importedWords,
                reusedWords = reusedWords,
                totalExamples = pkg.entries.sumOf { it.examples.size },
                importedExamples = importedExamples,
            )
        }
    }

    /**
     * 查找或创建生词本
     */
    private fun findOrCreateWordBook(name: String, now: Long): Long {
        val existing = database.wordBookQueries
            .selectWordBookByName(name)
            .executeAsOneOrNull()

        return if (existing != null) {
            existing.wordBookId
        } else {
            database.wordBookQueries.insertOriginalWordBook(name, null, now, now)
            database.wordBookQueries.selectLastInsertRowId().executeAsOne()
        }
    }

    /** 本内当前最大 entryOrder + 1（空本从 0 起）。 */
    private fun nextEntryOrder(wordBookId: Long): Long =
        database.wordBookEntryQueries
            .maxEntryOrder(wordBookId) { max -> max ?: -1L }
            .executeAsOne() + 1L

    /** 查找或创建 Word；返回 (wordId, 是否新建)。 */
    private fun findOrCreateWord(entry: VideoImportEntry, now: Long): Pair<Long, Boolean> {
        val normalized = entry.word.toNormalizedWordText()
        val existingWord = database.wordQueries
            .selectByNormalizedText(normalized)
            .executeAsOneOrNull()
        if (existingWord != null) return existingWord.wordId to false

        database.wordQueries.insertWord(
            text = entry.word.trim(),
            normalizedText = normalized,
            ipaAm = null,
            ipaBr = null,
            pronunciationAudioUri = null,
            createdAt = now,
            updatedAt = now,
        )
        val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
        return wordId to true
    }

    /** 词是否已在本中。 */
    private fun isEntryInWordBook(wordBookId: Long, wordId: Long): Boolean =
        database.wordBookEntryQueries
            .selectEntryByWord(wordBookId, wordId)
            .executeAsOneOrNull() != null

    /**
     * 导入单个词条：释义 + 例句 + 本关联 + 释义/例句全选（视频语境完整保留）。
     *
     * @return 导入的例句数
     */
    private fun importEntry(
        entry: VideoImportEntry,
        wordId: Long,
        ctx: EntryContext,
    ): Int {
        // 释义（单释义包：definitionOrder 恒 0）
        val posOrder = getPartOfSpeechOrder(entry.partOfSpeech)
        database.definitionEntryQueries.insertDefinitionEntry(
            wordId = wordId,
            partOfSpeech = entry.partOfSpeech,
            partOfSpeechOrder = posOrder.toLong(),
            definitionOrder = 0L,
            meaningEN = entry.meaningEN,
            meaningCN = entry.meaningCN,
        )
        val definitionEntryId = database.definitionEntryQueries
            .selectLastInsertRowId()
            .executeAsOne()

        // 例句（audioUri 直读 APK assets）
        val exampleIds = mutableListOf<Long>()
        for ((exampleIndex, example) in entry.examples.withIndex()) {
            database.exampleQueries.insertExample(
                definitionEntryId = definitionEntryId,
                sentence = example.sentence,
                chineseTranslation = example.chineseTranslation,
                sourceType = "REAL_MOVIE_TV",
                sourceRef = ctx.sourceVideo,
                licenseNote = null,
                audioUri = example.audioFile?.let { "asset://video_import/audio/$it" },
                audioDurationMs = null,
                exampleOrder = exampleIndex.toLong(),
            )
            exampleIds.add(
                database.exampleQueries.selectLastInsertRowId().executeAsOne(),
            )
        }

        // 本关联 + 全选
        database.wordBookEntryQueries.insertEntry(
            wordBookId = ctx.wordBookId,
            wordId = wordId,
            entryOrder = ctx.entryOrder,
            pendingTranslation = null,
            addedAt = ctx.now,
        )
        val newEntryId = database.wordBookEntryQueries
            .selectLastInsertRowId()
            .executeAsOne()
        database.wordBookEntryDefinitionQueries.insertEntryDefinition(
            wordBookEntryId = newEntryId,
            definitionEntryId = definitionEntryId,
        )
        for (exampleId in exampleIds) {
            database.wordBookEntryExampleSelectionQueries.insertExampleSelection(
                wordBookEntryId = newEntryId,
                exampleId = exampleId,
            )
        }
        return exampleIds.size
    }

    /**
     * 获取词性排序值（表序 +1；未知词性排最后）
     */
    private fun getPartOfSpeechOrder(partOfSpeech: String): Int {
        val index = PART_OF_SPEECH_ORDER.indexOfFirst { partOfSpeech.lowercase() in it }
        return if (index >= 0) index + 1 else POS_ORDER_UNKNOWN
    }

    /** 词条导入上下文（事务内共享的包级参数）。 */
    private data class EntryContext(
        val wordBookId: Long,
        val sourceVideo: String,
        val entryOrder: Long,
        val now: Long,
    )

    private companion object {
        /** 词性排序表（全称 + 缩写形，小写匹配）：noun=1 … phrase=10，与导入包既有排序值一致。 */
        val PART_OF_SPEECH_ORDER: List<Set<String>> = listOf(
            setOf("noun", "n."),
            setOf("verb", "v."),
            setOf("adjective", "adj."),
            setOf("adverb", "adv."),
            setOf("pronoun", "pron."),
            setOf("preposition", "prep."),
            setOf("conjunction", "conj."),
            setOf("interjection", "interj."),
            setOf("determiner", "det."),
            setOf("phrase", "phr."),
        )

        /** 未知词性排序值（排最后）。 */
        const val POS_ORDER_UNKNOWN: Int = 99
    }
}
