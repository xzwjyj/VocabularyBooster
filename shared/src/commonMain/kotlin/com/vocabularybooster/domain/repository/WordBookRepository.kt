package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.WordBookSelectionSnapshot
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * 生词本仓储端口（Phase 2，FR-4/FR-5；Phase 3 Step 5D 增派生三方法）。
 * 业务规则（删除守卫、保存校验、事务原子性）只存在于 shared（架构铁律 2）。
 */
@Suppress("TooManyFunctions") // 端口 13 方法：Phase 2 管理 7 + Step 5D 派生 3 + getWordBookName 1 + Phase 8.5 编辑 2
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

    /** 母本名（派生命名基名，Phase 3 Step 5D）。本不存在 → null。 */
    public suspend fun getWordBookName(wordBookId: Long): String?

    /** 精确重名计数（派生命名冲突探测，LE spec §8 分支 B 步骤 1）。 */
    public suspend fun countBooksWithName(name: String): Int

    /**
     * 派生 DERIVED 本（LE spec §8 分支 B 步骤 2–5，Phase 3 Step 5D，**单事务**）：
     * 事务首步 `countEffectiveRemaining`（Q5d 交集守卫，2026-09-04 裁决）——
     * 复制集合 = 当前母本仍存在的 WordBookEntry ∩ SessionWord(status != MASTERED)；
     * **交集为 0 → 返回 null，不建空 DERIVED 本（Case 3）**。
     * 非空则 `insertDerivedWordBook`（type=DERIVED + parentWordBookId + sourceSessionId 血缘）+
     * 复制 WordBookEntry（交集词，entryOrder/pendingTranslation 恒取自母本行）+
     * 复制 WordBookEntryDefinition + WordBookEntryExampleSelection（逐 ID 一致）——
     * 全部或全无；**不复制** WordMastery（D4），**不触碰** Word/DefinitionEntry/Example（D2）。
     * 分支裁决（A 零掌握不派生）与命名规则在 [com.vocabularybooster.learning.WordBookDeriver]，
     * 本方法只做事务性落库。母本不存在 → [RepositoryValidationException]（先于交集守卫）。
     */
    public suspend fun deriveWordBook(
        parentWordBookId: Long,
        sourceSessionId: Long,
        name: String,
        createdAt: Instant,
    ): Long?

    /**
     * 词条当前选择快照（FR-17，Phase 8.5）：编辑界面预填输入。
     * 词不在本内 → null；selections 释义按 FR-2 排序，例句经 Example 归属回各释义
     * （选择行不存释义归属，与播放装配 Q4/Q4b 同口径）。只读，不引入写事务。
     */
    public suspend fun getWordSelections(wordBookId: Long, wordId: Long): WordBookSelectionSnapshot?

    /**
     * 词条选择编辑（FR-17，Phase 8.5，**单事务定向替换**）：整组替换该（本,词）的
     * WordBookEntryDefinition + WordBookEntryExampleSelection 两类选择行；
     * **不重建 WordBookEntry 行**——entryOrder/addedAt/pendingTranslation 原样（编辑不改队列位置）。
     * 校验镜像保存流（[SaveRequestValidator] 同规则）：词/本存在、释义属词、例句属释义；
     * 空 selections（全不勾）→ [RepositoryValidationException]（不需要该词请用 [removeWordFromWordBook]）。
     * 掌握状态（WordMastery/SessionWord）零接触（D4 与选择集正交）。
     */
    public suspend fun updateWordSelections(
        wordBookId: Long,
        wordId: Long,
        selections: List<DefinitionSelection>,
    )
}
