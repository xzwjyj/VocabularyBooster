package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordPlacement
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.StudyQueueSnapshot
import kotlinx.datetime.Instant

/**
 * 学习会话仓储端口（Phase 3，FR-6/FR-11）。
 * 只负责会话/队列行的持久化与确定性读取（含崩溃恢复地基）；
 * 队列构建、分组、掌握裁决、完成检测、退出派生全部属学习引擎，不在本层。
 */
@Suppress("TooManyFunctions") // 会话域读写端口：Step 1/3/4/5A/5B/5C 最小增量累计 15 个方法，职责内聚不再拆分
public interface LearningSessionRepository {

    /**
     * 创建会话并物化队列（单事务，DATABASE_SCHEMA §4）：INSERT LearningSession(ACTIVE)
     * + 每词一条 SessionWord(PENDING)。全部成功或全部失败，无半成品状态。
     *
     * ACTIVE 唯一性（LE spec §3 引擎不变量）：事务内先查 ACTIVE 会话，已存在 →
     * [ActiveSessionExistsException]（携带既有会话 ID），数据库不留任何新会话数据。
     *
     * 前置输入由引擎保证（队列 = Q2 未掌握词、分组已固化）；本层校验：
     * wordBookId 存在、groupSize ≥ 1、words 非空，违反 → [RepositoryValidationException]。
     */
    public suspend fun createSession(
        wordBookId: Long,
        groupSize: Int,
        words: List<SessionWordPlacement>,
    ): Long

    /** 按 ID 查会话；不存在返回 null。 */
    public suspend fun getSession(sessionId: Long): LearningSession?

    /** 当前 ACTIVE 会话（恢复流程入口，LE spec §9）；无则 null。 */
    public suspend fun getActiveSession(): LearningSession?

    /**
     * 恢复快照（NFR-3）：会话 + 全部 SessionWord（(groupIndex, orderInGroup) 升序），
     * 单事务一致性读取；会话不存在返回 null。不重算、不修补 group/order——
     * 恢复裁决（书仍在、词仍在等完整性检查）属学习引擎（LE spec §9）。
     */
    public suspend fun getSessionWithWords(sessionId: Long): SessionSnapshot?

    /** 会话全部队列行，(groupIndex, orderInGroup) 升序；会话不存在返回空列表。 */
    public suspend fun getSessionWords(sessionId: Long): List<SessionWord>

    /** 指定组的队列行，orderInGroup 升序；组无词返回空列表。 */
    public suspend fun getSessionWordsByGroup(sessionId: Long, groupIndex: Int): List<SessionWord>

    /** 单个会话词；不存在返回 null。 */
    public suspend fun getSessionWord(sessionId: Long, wordId: Long): SessionWord?

    /**
     * 更新会话状态并写 endedAt（注入 Clock 当前时刻）。
     * 会话不存在 → [RepositoryValidationException]；status=ACTIVE → 拒绝
     * （ACTIVE 只能经 [createSession] 产生，endedAt 语义不成立）。
     * 状态机裁决（何时 COMPLETED/ABANDONED）属学习引擎（LE spec §7/§8）。
     * v1 无删除会话入口：终止 = 置 ABANDONED（DOMAIN_MODEL §7）。
     */
    public suspend fun updateSessionStatus(sessionId: Long, status: SessionStatus)

    /**
     * 更新会话词状态：MASTERED 时写 masteredAt（注入 Clock），否则置 null。
     * 行不存在 → [RepositoryValidationException]。SKIPPED 为 schema 预留状态，
     * 底层可表达/读取，但 v1 无产生它的业务入口（DOMAIN_MODEL §8.3）。
     *
     * 注意：这是无守卫的底层写——重复调用 MASTERED 会刷新 masteredAt；
     * 业务正确的幂等掌握标记必须走 [markSessionWordMastered]（MasteryMarker 专用通道）。
     */
    public suspend fun updateSessionWordStatus(sessionId: Long, wordId: Long, status: SessionWordStatus)

    /**
     * 原子掌握标记（LE spec §6 / DATABASE_SCHEMA §4 事务规则，单事务）：
     * 1. 条件 UPDATE `status != 'MASTERED'` 的 SessionWord → MASTERED + masteredAt（LE spec §6 语句 1）；
     * 2. `INSERT OR IGNORE WordMastery(wordBookId, wordId, masteredAt)`（wordBookId 取自会话行，复合主键天然幂等）。
     *
     * 返回 true = 本次完成转换并落 WordMastery；false = 该词已 MASTERED（幂等：
     * masteredAt 保持首次值、WordMastery 保持原行，不产生新语义事件）。
     * 会话或会话词不存在 → [RepositoryValidationException]。
     * masteredAt 由调用方（MasteryMarker，注入 Clock）传入——仓储不取系统时间（铁律 10）。
     */
    public suspend fun markSessionWordMastered(sessionId: Long, wordId: Long, masteredAt: Instant): Boolean

    /**
     * 建队输入快照（Phase 3 Step 4，LE spec §3）：本存在性 + 总词条数 + Q2 未掌握队列
     * （`selectStudyQueue` 原样，entryOrder ASC），单事务一致性读取。
     * 供引擎装配 StudyQueueBuilder 输入；队列裁决（空本/全掌握）属 Builder，不在此层。
     */
    public suspend fun getStudyQueueSnapshot(wordBookId: Long): StudyQueueSnapshot

    /**
     * 原子播放位迁移（Phase 3 Step 5A，LE spec §5 cur / §8.3，单事务）：
     * 语句 1 现播放词（若有）回到 PENDING——Next 跳过的词留在组内循环；
     * 语句 2 目标词置 PLAYING。当前位由此持久化推导（崩溃恢复/引擎重建零内存态）。
     * 目标词已 MASTERED 或行不存在 → [RepositoryValidationException]（回滚，位不漂移）；
     * 推进选择（选哪个词）属引擎 §5 nextWord，本层只落位。
     */
    public suspend fun setPlayingWord(sessionId: Long, wordId: Long)

    /**
     * Q3 书级未掌握词条数（LE spec §7 WordBookCompleted 判据输入，Phase 3 Step 5A）。
     * 只读数——「= 0 即书完成」的裁决属 CompletionDetector.isBookComplete，不在此层。
     */
    public suspend fun countUnmasteredEntries(wordBookId: Long): Int

    /**
     * 终态迁移（LE spec §8 退出三分支的落库半程，Phase 3 Step 5C，单事务）：
     * 仅当会话当前 ACTIVE 时写入终态 + endedAt（注入 Clock 当前时刻）；
     * 已终态（COMPLETED/ABANDONED）→ **不写入不刷新 endedAt**（幂等：首次终态时刻保持），
     * 原样返回既有会话行。
     * 会话不存在 → [RepositoryValidationException]；status=ACTIVE → 拒绝
     * （终态语义，与 [updateSessionStatus] 契约一致）。
     * 分支裁决（选 COMPLETED 还是 ABANDONED）属引擎（CompletionDetector 唯一权威）；
     * SessionWord/WordMastery 不在触碰范围（学习历史保留，LE spec §8）。
     */
    public suspend fun terminateSessionIfActive(sessionId: Long, status: SessionStatus): LearningSession

    /**
     * 恢复完整性检查（LE spec §9/§10-8/§10-9，TC-LE-10，Phase 3 Step 5B，单事务）：
     * ① 书不存在 → 会话置 ABANDONED + endedAt，返回 [SessionRecoveryResult.BookDeleted]
     *    （安全 ABANDON——检查与落库同事务，无 crash window）；
     * ② 书存在 → 删除对应 WordBookEntry 已不存在的 SessionWord 行（任何状态，含
     *    MASTERED/PLAYING），返回剔除后快照 [SessionRecoveryResult.Recovered]——
     *    其余词 groupIndex/orderInGroup 原样保留，**不重编号**；
     * WordMastery 不在触碰范围——移词路径（removeWordFromWordBook）已同步自理。
     * 会话不存在或非 ACTIVE → [RepositoryValidationException]（调用前置由引擎保证）。
     * 幂等：重复调用无悬挂行可删；已 ABANDONED 会话不会再次进入本方法（引擎前置短路）。
     */
    public suspend fun recoverSessionIntegrity(sessionId: Long): SessionRecoveryResult
}

/** 恢复完整性裁决结果（LE spec §9，Phase 3 Step 5B）。 */
public sealed interface SessionRecoveryResult {

    /** 书仍在：剔除悬挂会话词后的完整快照——其余照常（§10-9，不整会话 ABANDON）。 */
    public data class Recovered(val snapshot: SessionSnapshot) : SessionRecoveryResult

    /** 书已不存在：会话已在本事务内置 ABANDONED + endedAt（安全退出，§10-8）。 */
    public data object BookDeleted : SessionRecoveryResult
}
