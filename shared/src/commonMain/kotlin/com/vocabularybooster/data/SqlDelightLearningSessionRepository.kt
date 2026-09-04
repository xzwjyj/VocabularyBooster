package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordPlacement
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.StudyQueueEntryRef
import com.vocabularybooster.domain.model.StudyQueueSnapshot
import com.vocabularybooster.domain.repository.ActiveSessionExistsException
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.SessionRecoveryResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * LearningSessionRepository 的 SQLDelight 实现（Phase 3，FR-6/FR-11）。
 * 会话物化事务原子性与 ACTIVE 唯一性检查在此层（LE spec §3：事务内先查后插，
 * 不设 DB 级约束）；队列/分组计算、掌握与完成裁决属学习引擎，本层只落库与读出。
 */
@Suppress("TooManyFunctions") // 端口 15 个方法的 1:1 实现（见端口 suppress 说明）
public class SqlDelightLearningSessionRepository(
    private val database: VocabularyDatabase,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LearningSessionRepository {

    override suspend fun createSession(
        wordBookId: Long,
        groupSize: Int,
        words: List<SessionWordPlacement>,
    ): Long = withContext(dispatcher) {
        if (groupSize < 1) {
            throw RepositoryValidationException("分组大小必须 ≥ 1：groupSize=$groupSize")
        }
        if (words.isEmpty()) {
            throw RepositoryValidationException("会话队列不能为空（空本/全掌握应由引擎在开始前拒绝）")
        }
        database.transactionWithResult {
            if (database.wordBookQueries.selectWordBookById(wordBookId).executeAsOneOrNull() == null) {
                throw RepositoryValidationException("生词本不存在：wordBookId=$wordBookId")
            }
            // ACTIVE 唯一性（引擎不变量）：同一事务内先查后插，两道防线之一（LE spec §3）
            val active = database.learningSessionQueries.selectActiveSession().executeAsOneOrNull()
            if (active != null) {
                throw ActiveSessionExistsException(active.sessionId)
            }
            val now = clock.now().toEpochMilliseconds()
            // insert + last_insert_rowid 同事务钉住连接（JDBC 文件驱动每操作换连接，
            // 跨语句读 rowid 恒为 0；事务内不受影响，Android 单连接驱动亦不受影响）
            database.learningSessionQueries.insertSession(
                wordBookId = wordBookId,
                status = SessionStatus.ACTIVE.name,
                groupSize = groupSize.toLong(),
                startedAt = now,
                endedAt = null,
            )
            val sessionId = database.learningSessionQueries.selectLastInsertRowId().executeAsOne()
            words.forEach { placement ->
                database.sessionWordQueries.insertSessionWord(
                    sessionId = sessionId,
                    wordId = placement.wordId,
                    groupIndex = placement.groupIndex.toLong(),
                    orderInGroup = placement.orderInGroup.toLong(),
                    status = SessionWordStatus.PENDING.name,
                )
            }
            sessionId
        }
    }

    override suspend fun getSession(sessionId: Long): LearningSession? = withContext(dispatcher) {
        database.learningSessionQueries.selectSessionById(sessionId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getActiveSession(): LearningSession? = withContext(dispatcher) {
        database.learningSessionQueries.selectActiveSession().executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getSessionWithWords(sessionId: Long): SessionSnapshot? = withContext(dispatcher) {
        // 单事务一致性读取：会话行与队列行取自同一快照，恢复读取确定性（NFR-3）
        database.transactionWithResult {
            val session = database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOneOrNull()?.toDomain()
                ?: return@transactionWithResult null
            SessionSnapshot(
                session = session,
                words = database.sessionWordQueries.selectSessionWords(sessionId)
                    .executeAsList().map { it.toDomain() },
            )
        }
    }

    override suspend fun getSessionWords(sessionId: Long): List<SessionWord> = withContext(dispatcher) {
        database.sessionWordQueries.selectSessionWords(sessionId).executeAsList().map { it.toDomain() }
    }

    override suspend fun getSessionWordsByGroup(
        sessionId: Long,
        groupIndex: Int,
    ): List<SessionWord> = withContext(dispatcher) {
        database.sessionWordQueries.selectSessionWordsByGroup(sessionId, groupIndex.toLong())
            .executeAsList().map { it.toDomain() }
    }

    override suspend fun getSessionWord(sessionId: Long, wordId: Long): SessionWord? = withContext(dispatcher) {
        database.sessionWordQueries.selectSessionWord(sessionId, wordId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun updateSessionStatus(sessionId: Long, status: SessionStatus): Unit = withContext(dispatcher) {
        if (status == SessionStatus.ACTIVE) {
            throw RepositoryValidationException("不能将会话更新为 ACTIVE（ACTIVE 只能由创建会产生）")
        }
        if (database.learningSessionQueries.selectSessionById(sessionId).executeAsOneOrNull() == null) {
            throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        }
        database.learningSessionQueries.updateSessionStatus(
            status = status.name,
            endedAt = clock.now().toEpochMilliseconds(),
            sessionId = sessionId,
        )
    }

    override suspend fun updateSessionWordStatus(
        sessionId: Long,
        wordId: Long,
        status: SessionWordStatus,
    ): Unit = withContext(dispatcher) {
        if (database.sessionWordQueries.selectSessionWord(sessionId, wordId).executeAsOneOrNull() == null) {
            throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        }
        database.sessionWordQueries.updateSessionWordStatus(
            status = status.name,
            masteredAt = if (status == SessionWordStatus.MASTERED) clock.now().toEpochMilliseconds() else null,
            sessionId = sessionId,
            wordId = wordId,
        )
    }

    override suspend fun markSessionWordMastered(
        sessionId: Long,
        wordId: Long,
        masteredAt: Instant,
    ): Boolean = withContext(dispatcher) {
        database.transactionWithResult {
            // 语句 1（LE spec §6）：条件 UPDATE——已 MASTERED 时 0 行受影响，masteredAt 不被触碰；
            // 受影响行数经 changes() 读取（2.0.2 的 UPDATE 生成函数返回 Unit），同事务同连接保证可见
            database.sessionWordQueries.markMastered(
                masteredAt = masteredAt.toEpochMilliseconds(),
                sessionId = sessionId,
                wordId = wordId,
            )
            val transitions = database.sessionWordQueries.selectChanges().executeAsOne()
            when (transitions) {
                0L -> {
                    // 区分「已掌握（幂等）」与「词不属于本会话（非法输入）」
                    if (database.sessionWordQueries.selectSessionWord(sessionId, wordId).executeAsOneOrNull() == null) {
                        throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
                    }
                    false
                }
                1L -> {
                    // 语句 2：WordMastery 落行（wordBookId 取自会话行；会话行缺失 = 中途失败 → 整体回滚）
                    val session = database.learningSessionQueries.selectSessionById(sessionId).executeAsOneOrNull()
                        ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
                    database.wordMasteryQueries.markMastered(
                        wordBookId = session.wordBookId,
                        wordId = wordId,
                        masteredAt = masteredAt.toEpochMilliseconds(),
                    )
                    true
                }
                else -> error("markSessionWordMastered 受影响行数非法：$transitions（PK 保证 ≤ 1）")
            }
        }
    }

    override suspend fun getStudyQueueSnapshot(wordBookId: Long): StudyQueueSnapshot = withContext(dispatcher) {
        // 单事务一致读取：本存在性、词条总数与 Q2 队列取自同一快照（排序由 Q2 保证，本层不重排）
        database.transactionWithResult {
            StudyQueueSnapshot(
                wordBookExists = database.wordBookQueries.selectWordBookById(wordBookId)
                    .executeAsOneOrNull() != null,
                totalEntryCount = database.wordBookEntryQueries.countEntriesForWordBook(wordBookId)
                    .executeAsOne().toInt(),
                unmasteredEntries = database.queriesQueries.selectStudyQueue(wordBookId)
                    .executeAsList()
                    .map { StudyQueueEntryRef(wordId = it.wordId, entryOrder = it.entryOrder.toInt()) },
            )
        }
    }

    override suspend fun setPlayingWord(sessionId: Long, wordId: Long): Unit = withContext(dispatcher) {
        // 单事务：清位 + 落位原子完成，半迁移（双 PLAYING/位漂移）不落库；
        // 校验失败抛出 → 整体回滚，播放位保持迁移前状态
        database.transactionWithResult {
            database.sessionWordQueries.clearPlaying(sessionId)
            database.sessionWordQueries.setPlaying(sessionId, wordId)
            val row = database.sessionWordQueries.selectSessionWord(sessionId, wordId)
                .executeAsOneOrNull()
                ?: throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
            if (row.status == SessionWordStatus.MASTERED.name) {
                throw RepositoryValidationException("已掌握词不能置为播放中：sessionId=$sessionId, wordId=$wordId")
            }
        }
    }

    override suspend fun countUnmasteredEntries(wordBookId: Long): Int = withContext(dispatcher) {
        database.queriesQueries.countUnmastered(wordBookId).executeAsOne().toInt()
    }

    override suspend fun terminateSessionIfActive(
        sessionId: Long,
        status: SessionStatus,
    ): LearningSession = withContext(dispatcher) {
        if (status == SessionStatus.ACTIVE) {
            throw RepositoryValidationException("终态不能为 ACTIVE（ACTIVE 只能由创建会产生）")
        }
        // 单事务：读取当前状态 + 校验 ACTIVE + 写终态/endedAt 原子完成（LE spec §8）；
        // 已终态 → 不写入不刷新 endedAt（幂等，首次终态时刻保持）
        database.transactionWithResult {
            val session = database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOneOrNull()
                ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
            if (session.status != SessionStatus.ACTIVE.name) {
                return@transactionWithResult session.toDomain()
            }
            val endedAt = clock.now()
            database.learningSessionQueries.updateSessionStatus(
                status = status.name,
                endedAt = endedAt.toEpochMilliseconds(),
                sessionId = sessionId,
            )
            session.toDomain().copy(status = status, endedAt = endedAt)
        }
    }

    override suspend fun recoverSessionIntegrity(sessionId: Long): SessionRecoveryResult =
        withContext(dispatcher) {
            // 单事务：书存在性裁决 + ABANDON 落库 / 悬挂行剔除 + 快照读回同快照完成——
            // 「检查后提交、再改再提交」的 crash window 不存在（LE spec §9）
            database.transactionWithResult {
                val session = database.learningSessionQueries.selectSessionById(sessionId)
                    .executeAsOneOrNull()
                    ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
                if (session.status != SessionStatus.ACTIVE.name) {
                    throw RepositoryValidationException(
                        "恢复完整性检查只针对 ACTIVE 会话：sessionId=$sessionId, status=${session.status}",
                    )
                }
                if (database.wordBookQueries.selectWordBookById(session.wordBookId).executeAsOneOrNull() == null) {
                    // §10-8 安全 ABANDON：不允许以 ACTIVE 状态继续学习一本不存在的书
                    database.learningSessionQueries.updateSessionStatus(
                        status = SessionStatus.ABANDONED.name,
                        endedAt = clock.now().toEpochMilliseconds(),
                        sessionId = sessionId,
                    )
                    return@transactionWithResult SessionRecoveryResult.BookDeleted
                }
                // §10-9 剔除悬挂词（任何状态）；其余 placement 原样保留，不重编号
                database.sessionWordQueries.deleteSessionWordsNotInBook(sessionId)
                SessionRecoveryResult.Recovered(
                    SessionSnapshot(
                        session = session.toDomain(),
                        words = database.sessionWordQueries.selectSessionWords(sessionId)
                            .executeAsList().map { it.toDomain() },
                    ),
                )
            }
        }
}
