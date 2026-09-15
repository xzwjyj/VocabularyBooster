package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.ActiveSessionExistsException
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.SessionRecoveryResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LearningEngine 默认实现（LEARNING_ENGINE_SPEC，Phase 3 Step 4/5A/5B/5D）。
 * 纯编排层：读状态 → 委托已验收组件（StudyQueueBuilder / GroupSplitter /
 * LearningSessionRepository / MasteryMarker / CompletionDetector / WordBookDeriver）→ 返回规格结果。
 * 本类不含任何业务规则——排序、建队、分组、掌握幂等、完成判据、恢复完整性裁决、
 * 分支 A/B 派生裁决各有唯一权威实现（架构铁律 2）。
 *
 * ACTIVE 唯一性两道防线（LE spec §3）：防线 1 = 物化事务内检查（Step 1 仓储）；
 * 防线 2 = 引擎串行化——会话状态变更（start/resume/mark/advance/exit）经 [sessionStateMutex] 串行执行。
 * 播放循环的注入 CoroutineScope 属 Phase 4（ARCHITECTURE §7），本步不引入。
 */
public class DefaultLearningEngine(
    private val sessionRepository: LearningSessionRepository,
    private val settingsRepository: LearningSettingsRepository,
    private val masteryMarker: MasteryMarker,
    private val wordBookDeriver: WordBookDeriver,
) : LearningEngine {

    private val sessionStateMutex = Mutex()

    override suspend fun startSession(wordBookId: Long): StartResult = sessionStateMutex.withLock {
        startSessionLocked(wordBookId)
    }

    // LE spec §3 规定的顺序前置裁决（本不存在/空本/全掌握/全关/ACTIVE 冲突）天然是多守卫子句——
    // 每个拒绝即返回，强行收敛单出口会掩盖裁决顺序
    @Suppress("ReturnCount")
    private suspend fun startSessionLocked(wordBookId: Long): StartResult {
        // ① 书侧裁决：装配 Builder 输入（数据来自仓储单事务快照，Q2 顺序原样）；
        //    本不存在 → Builder 沿用 domain 契约抛出；空本/全掌握 → Builder 拒绝（LE spec §3）
        val data = sessionRepository.getStudyQueueSnapshot(wordBookId)
        val queueWords = when (
            val queue = StudyQueueBuilder.build(
                WordBookStudySnapshot(
                    wordBookId = wordBookId,
                    wordBookExists = data.wordBookExists,
                    totalEntryCount = data.totalEntryCount,
                    studyQueue = data.studyEntries.map {
                        StudyQueueWord(wordId = it.wordId, entryOrder = it.entryOrder)
                    },
                ),
            )
        ) {
            is StudyQueueResult.Rejected -> return StartResult.Rejected(
                when (queue.reason) {
                    StudyQueueResult.Reason.EMPTY_BOOK -> StartResult.Reason.EMPTY_BOOK
                },
            )
            is StudyQueueResult.Queue -> queue.words
        }
        // ② 设置裁决：六开关全关拒绝（LE spec §3）；groupSize 取设置值（默认 10 属 AppSetting）
        if (!settingsRepository.getPlaybackToggles().anyEnabled) {
            return StartResult.Rejected(StartResult.Reason.PLAYBACK_DISABLED)
        }
        val groupSize = settingsRepository.getGroupSize()
        // ③ 分组固化 + 物化：事务原子性与 ACTIVE 检查由 Step 1 仓储保证（引擎不开事务）
        val placements = GroupSplitter.split(queueWords, groupSize)
        val sessionId = try {
            sessionRepository.createSession(
                wordBookId = wordBookId,
                groupSize = groupSize,
                words = placements,
            )
        } catch (e: ActiveSessionExistsException) {
            return StartResult.Rejected(StartResult.Reason.ACTIVE_SESSION_EXISTS(e.activeSessionId))
        }
        // ④ 读回持久化快照：引擎不缓存内存态，结果即库中真相
        val snapshot = sessionRepository.getSessionWithWords(sessionId)
        return StartResult.Started(
            snapshot ?: error("会话创建后快照读回失败：sessionId=$sessionId（物化事务已提交，不应发生）"),
        )
    }

    override suspend fun resumeSession(sessionId: Long): ResumeResult = sessionStateMutex.withLock {
        // 前置（既有契约，Step 4）：不存在 → NOT_FOUND；非 ACTIVE → NOT_ACTIVE
        val snapshot = sessionRepository.getSessionWithWords(sessionId)
        when {
            snapshot == null -> ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_FOUND)
            snapshot.session.status != SessionStatus.ACTIVE ->
                ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_ACTIVE)
            // LE spec §9 完整性检查（恢复时发生，TC-LE-10）：书删 → 安全 ABANDON +
            // BOOK_DELETED 提示；词移除 → 剔除悬挂行后照常恢复（§10-9，不整会话 ABANDON）。
            // 检查、剔除/落终态、快照读回由仓储单事务完成，无 crash window
            else -> when (val outcome = sessionRepository.recoverSessionIntegrity(sessionId)) {
                is SessionRecoveryResult.BookDeleted ->
                    ResumeResult.Rejected(ResumeResult.Reason.BOOK_DELETED)
                is SessionRecoveryResult.Recovered ->
                    ResumeResult.Resumed(outcome.snapshot)
            }
        }
    }

    override suspend fun markMastered(
        sessionId: Long,
        wordId: Long,
        source: MasterySource,
    ): MasteryResult = sessionStateMutex.withLock {
        masteryMarker.markMastered(sessionId, wordId, source)
    }

    override suspend fun advance(sessionId: Long): AdvanceResult = sessionStateMutex.withLock {
        advanceLocked(sessionId)
    }

    override suspend fun exitSession(sessionId: Long): ExitResult = sessionStateMutex.withLock {
        exitSessionLocked(sessionId)
    }

    @Suppress("ReturnCount") // 退出分支三分支（A/B 完成 / C 完成 / 已终态幂等）+ 分支 B 派生
    /**
     * LE spec §8 退出三分支：
     * 2026-09-15: 改用 session snapshot 判断完成（ADR-002），不再使用 Q3（book-level）。
     * 分支选择基于当前会话的 snapshot（SessionWord 列表）——全部 MASTERED → COMPLETED；否则 ABANDONED。
     * 终态落库为仓储单事务原子操作；已终态幂等 no-op（endedAt 不刷新）。
     * ABANDONED 后分支 A/B 的裁决与派生（Step 5D）委托 [WordBookDeriver]：
     * 零掌握 → null（不建空本）；部分掌握 → DERIVED 本 ID；**复制交集为空
     * （Case 3 裁决 2026-09-04）→ null（不建空本）**。终态事务与派生事务分离（DATABASE_SCHEMA §4）。
     */
    private suspend fun exitSessionLocked(sessionId: Long): ExitResult {
        val snapshot = sessionRepository.getSessionWithWords(sessionId)
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        // 已终态（COMPLETED/ABANDONED）重复退出：幂等 no-op，零写入（endedAt 保持首次终态时刻）
        if (snapshot.session.status != SessionStatus.ACTIVE) return ExitResult(derivedWordBookId = null)
        // 2026-09-15: 用 session snapshot 判断，不再查 Q3（实现"母本永远可学"）
        val terminal = if (CompletionDetector.isSessionComplete(snapshot.words)) {
            SessionStatus.COMPLETED // 分支 C：会话 snapshot 全部掌握
        } else {
            SessionStatus.ABANDONED // 分支 A/B：保存会话状态与学习历史
        }
        sessionRepository.terminateSessionIfActive(sessionId, terminal)
        if (terminal != SessionStatus.ABANDONED) return ExitResult(derivedWordBookId = null)
        return ExitResult(
            derivedWordBookId = wordBookDeriver.derive(snapshot.session, snapshot.words), // 分支 A/B（Step 5D）
        )
    }

    // LE spec §5 nextWord 的位置裁决天然多分支（终态幂等 / 会话完成 / 组内顺序 / 回绕），
    // 每个裁决即返回，收敛单出口会掩盖规格结构
    @Suppress("ReturnCount")
    private suspend fun advanceLocked(sessionId: Long): AdvanceResult {
        val snapshot = sessionRepository.getSessionWithWords(sessionId)
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        // 终态会话重复 advance：幂等只读，不触碰状态（endedAt 不刷新、位不迁移）
        if (snapshot.session.status != SessionStatus.ACTIVE) return AdvanceResult.BookComplete
        // 完成判据唯一权威 = CompletionDetector（LE spec §7）——引擎不复制 all{} 规则。
        // words 为空 = §5「words.isEmpty() → BookComplete」直译：Step 5B 恢复剔除后
        // 会话队列可能全空（此前 createSession 保证 ≥1 词，该分支不可达）
        if (snapshot.words.isEmpty() || CompletionDetector.isSessionComplete(snapshot.words)) {
            return completeSessionLocked(snapshot)
        }
        // §5 nextWord 选择：只在未掌握词中进行（v1 status != MASTERED ≡ {PENDING, PLAYING}）
        val unmastered = snapshot.words.filter { it.status != SessionWordStatus.MASTERED }
        val group = unmastered.minOf { it.groupIndex } // 当前组 = 最小还有未掌握词的组；组空自动进入下一组
        val inGroup = unmastered.filter { it.groupIndex == group }
        val playing = inGroup.firstOrNull { it.status == SessionWordStatus.PLAYING }
        // cur = 持久化播放位（无播放词 = 开场/掌握后顺延 → 组内最小）；播到组尾回绕组内首个未掌握词
        val next = inGroup.firstOrNull { playing == null || it.orderInGroup > playing.orderInGroup }
            ?: inGroup.first()
        sessionRepository.setPlayingWord(sessionId, next.wordId)
        return AdvanceResult.NextWord(
            ref = WordRef(sessionId, next.wordId, next.groupIndex, next.orderInGroup),
            completedGroupIndex = completedGroupIndexBefore(snapshot.words, next.groupIndex),
        )
    }

    /**
     * §7 会话级完成：使用 session snapshot 判断（ADR-002 实现"母本永远可学"）。
     * 2026-09-15: 不再使用 Q3（book-level），改为基于 SessionWord snapshot 判断。
     * 会话置 COMPLETED + endedAt。动作序列中的停止播放（Phase 4）与勋章（Phase 6）不在本步。
     */
    private suspend fun completeSessionLocked(snapshot: SessionSnapshot): AdvanceResult {
        // 2026-09-15: 用 session snapshot 判断（实现"母本永远可学"）
        sessionRepository.updateSessionStatus(snapshot.session.sessionId, SessionStatus.COMPLETED)
        return AdvanceResult.BookComplete
    }

    /** 离开的组已无未掌握词 → 携带其组号（§7 GroupCompleted 载体；裁决权在 CompletionDetector）。 */
    private fun completedGroupIndexBefore(words: List<SessionWord>, targetGroup: Int): Int? {
        if (targetGroup == 0) return null
        val previous = words.filter { it.groupIndex == targetGroup - 1 }
        return if (CompletionDetector.isGroupComplete(previous)) targetGroup - 1 else null
    }
}
