package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import kotlinx.coroutines.flow.Flow

/**
 * 生词本仓储端口（Phase 2，FR-4/FR-5）。
 * 业务规则（删除守卫、保存校验、事务原子性）只存在于 shared（架构铁律 2）。
 */
public interface WordBookRepository {

    /** 生词本列表（含词条数），SQLDelight 响应式流（ARCHITECTURE §7）。 */
    public fun observeWordBooks(): Flow<List<WordBookSummary>>

    public suspend fun getWordBooks(): List<WordBookSummary>

    /** 创建 ORIGINAL 本（FR-4）。名称空白 → [RepositoryValidationException]。 */
    public suspend fun createWordBook(name: String): Long

    /** 重命名（FR-4）。本不存在 → [RepositoryValidationException]。 */
    public suspend fun renameWordBook(wordBookId: Long, newName: String)

    /**
     * 删除生词本（FR-4）：只删关系数据，绝不删底层 Word（DOMAIN_MODEL §7）。
     * 有完成勋章或存在派生子本 → [WordBookDeletionException]。
     */
    public suspend fun deleteWordBook(wordBookId: Long)

    /** 本内词条（entryOrder 升序）。 */
    public suspend fun getWordBookWords(wordBookId: Long): List<WordBookWord>

    /** 从本内移除词（关系删除 + 本内掌握行清理；Word 不动）。 */
    public suspend fun removeWordFromWordBook(wordBookId: Long, wordId: Long)

    /**
     * 保存流（FR-5，单事务）：词 → 多本 + 释义选择 + 例句逐条选择（PROJECT_SPEC v1.3）。
     * 未选本 / 未选释义 / 例句不属于所选释义 → [RepositoryValidationException]。
     */
    public suspend fun saveWordToBooks(request: SaveWordRequest)
}
