package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.GrantAchievementResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AchievementRepository 的 SQLDelight 实现（Phase 6，FR-13）。
 * 授予幂等 = 预检 + INSERT OR IGNORE + 唯一索引三层（DATABASE_SCHEMA §4）；
 * 行 → 领域映射（payloadJson 解析 / type 未知值 fail-fast）见 Mappers.kt。
 */
public class SqlDelightAchievementRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AchievementRepository {

    override suspend fun grantBookCompleted(
        wordBookId: Long,
        payload: BookCompletedPayload,
    ): GrantAchievementResult = withContext(dispatcher) {
        database.transactionWithResult {
            // 第一层幂等（预检）：已授予零写入、零事件
            database.achievementQueries.selectByTypeAndBook(TYPE_BOOK_COMPLETED, wordBookId)
                .executeAsOneOrNull()
                ?.let { return@transactionWithResult GrantAchievementResult(it.toDomain(), firstGrant = false) }
            // earnedAt = finishedAt（会话完成时刻）：确定性——重放/补发不漂移（ACHIEVEMENT_SPEC v1.1）
            database.achievementQueries.insertAchievement(
                type = TYPE_BOOK_COMPLETED,
                wordBookId = wordBookId,
                payloadJson = payload.toJsonString(),
                earnedAt = payload.finishedAt.toEpochMilliseconds(),
            )
            // 第二/三层（OR IGNORE + 唯一索引）兜底并发；v1 单引擎顺序消费，此处回读必为本事务新行
            val granted = database.achievementQueries
                .selectByTypeAndBook(TYPE_BOOK_COMPLETED, wordBookId).executeAsOne()
            GrantAchievementResult(granted.toDomain(), firstGrant = true)
        }
    }

    override suspend fun getAchievements(): List<Achievement> = withContext(dispatcher) {
        database.achievementQueries.selectAllAchievements().executeAsList().map { it.toDomain() }
    }

    override suspend fun getBookCompletedFor(wordBookId: Long): Achievement? = withContext(dispatcher) {
        database.achievementQueries.selectByTypeAndBook(TYPE_BOOK_COMPLETED, wordBookId)
            .executeAsOneOrNull()?.toDomain()
    }

    private companion object {
        const val TYPE_BOOK_COMPLETED = "BOOK_COMPLETED"
    }
}
