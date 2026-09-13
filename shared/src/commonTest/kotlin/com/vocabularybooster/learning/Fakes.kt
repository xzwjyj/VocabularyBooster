package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordPlacement
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.StudyQueueSnapshot
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.repository.ActiveSessionExistsException
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.SessionRecoveryResult
import com.vocabularybooster.domain.repository.WordBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** 可推进固定时钟（铁律 10：引擎内时间一律注入；与 jvmTest TestSupport 同语义）。 */
internal class FixedClock(private var nowMillis: Long = 1_760_000_000_000L) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    fun advanceMillis(delta: Long) {
        nowMillis += delta
    }
}

/**
 * 手写 Fake 端口（CLAUDE.md 测试规范：只用手写 Fake，不用 mock 框架）。
 * 按端口契约模拟内存语义，供 commonTest 驱动 learning 层组件；
 * 真实 SQLDelight 事务语义（原子性/回滚）由 jvmTest 的 JDBC 用例锁定。
 */
internal class FakeLearningSessionRepository : LearningSessionRepository {

    val sessions = mutableMapOf<Long, LearningSession>()
    val words = mutableMapOf<Long, MutableList<SessionWord>>()

    /** (wordBookId, wordId) → masteredAt 毫秒；行存在 = 已掌握（DOMAIN_MODEL §2.7）。 */
    val mastery = mutableMapOf<Pair<Long, Long>, Long>()

    /** 建队输入快照（getStudyQueueSnapshot 数据源；未登记的 wordBookId = 本不存在）。 */
    val studySnapshots = mutableMapOf<Long, StudyQueueSnapshot>()

    /** Q3 书级未掌握数数据源（countUnmasteredEntries；未登记默认 0 = 会话队列即全书未掌握）。 */
    val unmasteredEntryCounts = mutableMapOf<Long, Int>()

    /** 恢复完整性数据源 Case A：已删除的本（书存在性判据；未登记 = 书存在）。 */
    val deletedBooks = mutableSetOf<Long>()

    /** 恢复完整性数据源 Case B：各本现存词条 wordId 集合（悬挂判据；未登记 = 会话词全部存在）。 */
    val bookEntryWordIds = mutableMapOf<Long, Set<Long>>()

    /** createSession 调用计数（resume 不重建会话的断言依据）。 */
    var createSessionCalls: Int = 0
        private set

    /** 注入 createSession 失败（引擎错误传播测试；抛出前不产生任何写入）。 */
    var createSessionFailure: Throwable? = null

    /** 底层 updateSessionWordStatus 探针：引擎掌握路径绝不经过它（Step 3 锁定的边界）。 */
    val updateSessionWordStatusCalls = mutableListOf<Triple<Long, Long, SessionWordStatus>>()

    /** 底层 updateSessionStatus 探针（advance 终态写入次数/幂等断言依据）。 */
    val updateSessionStatusCalls = mutableListOf<Pair<Long, SessionStatus>>()

    /** setPlayingWord 调用记录（播放位迁移断言依据）。 */
    val setPlayingWordCalls = mutableListOf<Pair<Long, Long>>()

    /** terminateSessionIfActive 调用记录（终态写入次数/退出幂等断言依据；已终态重入不记录）。 */
    val terminateSessionCalls = mutableListOf<Pair<Long, SessionStatus>>()

    private var nextSessionId = 1L

    override suspend fun createSession(
        wordBookId: Long,
        groupSize: Int,
        words: List<SessionWordPlacement>,
    ): Long {
        createSessionCalls++
        createSessionFailure?.let { throw it }
        if (groupSize < 1) throw RepositoryValidationException("分组大小必须 ≥ 1：groupSize=$groupSize")
        if (words.isEmpty()) throw RepositoryValidationException("会话队列不能为空")
        sessions.values.firstOrNull { it.status == SessionStatus.ACTIVE }?.let {
            throw ActiveSessionExistsException(it.sessionId)
        }
        val id = nextSessionId++
        sessions[id] = LearningSession(
            sessionId = id,
            wordBookId = wordBookId,
            status = SessionStatus.ACTIVE,
            groupSize = groupSize,
            startedAt = Instant.fromEpochMilliseconds(0),
        )
        this.words[id] = words.map {
            SessionWord(id, it.wordId, it.groupIndex, it.orderInGroup, SessionWordStatus.PENDING)
        }.toMutableList()
        return id
    }

    override suspend fun getSession(sessionId: Long): LearningSession? = sessions[sessionId]

    override suspend fun getActiveSession(): LearningSession? =
        sessions.values.firstOrNull { it.status == SessionStatus.ACTIVE }

    override suspend fun getSessionWithWords(sessionId: Long): SessionSnapshot? =
        sessions[sessionId]?.let { SessionSnapshot(it, orderedWords(sessionId)) }

    override suspend fun getSessionWords(sessionId: Long): List<SessionWord> = orderedWords(sessionId)

    override suspend fun getSessionWordsByGroup(sessionId: Long, groupIndex: Int): List<SessionWord> =
        orderedWords(sessionId).filter { it.groupIndex == groupIndex }

    override suspend fun getSessionWord(sessionId: Long, wordId: Long): SessionWord? =
        words[sessionId]?.firstOrNull { it.wordId == wordId }

    override suspend fun updateSessionStatus(sessionId: Long, status: SessionStatus) {
        if (status == SessionStatus.ACTIVE) throw RepositoryValidationException("不能将会话更新为 ACTIVE")
        val existing = sessions[sessionId]
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        updateSessionStatusCalls += sessionId to status
        sessions[sessionId] = existing.copy(status = status, endedAt = Instant.fromEpochMilliseconds(0))
    }

    override suspend fun updateSessionWordStatus(sessionId: Long, wordId: Long, status: SessionWordStatus) {
        updateSessionWordStatusCalls += Triple(sessionId, wordId, status)
        val list = words[sessionId]
            ?: throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        val index = list.indexOfFirst { it.wordId == wordId }
        if (index < 0) throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        list[index] = list[index].copy(
            status = status,
            masteredAt = if (status == SessionWordStatus.MASTERED) Instant.fromEpochMilliseconds(0) else null,
        )
    }

    override suspend fun markSessionWordMastered(sessionId: Long, wordId: Long, masteredAt: Instant): Boolean {
        val list = words[sessionId]
            ?: throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        val index = list.indexOfFirst { it.wordId == wordId }
        if (index < 0) throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        if (list[index].status == SessionWordStatus.MASTERED) return false // 条件 UPDATE 0 行 → 幂等
        val session = sessions[sessionId]
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId") // 孤儿会话词（FK off 场景）
        list[index] = list[index].copy(status = SessionWordStatus.MASTERED, masteredAt = masteredAt)
        mastery.putIfAbsent(session.wordBookId to wordId, masteredAt.toEpochMilliseconds()) // INSERT OR IGNORE
        return true
    }

    private fun orderedWords(sessionId: Long): List<SessionWord> =
        words[sessionId].orEmpty().sortedWith(compareBy({ it.groupIndex }, { it.orderInGroup }))

    override suspend fun getStudyQueueSnapshot(wordBookId: Long): StudyQueueSnapshot =
        studySnapshots[wordBookId] ?: StudyQueueSnapshot(
            wordBookExists = false,
            totalEntryCount = 0,
            unmasteredEntries = emptyList(),
        )

    override suspend fun setPlayingWord(sessionId: Long, wordId: Long) {
        val list = words[sessionId]
            ?: throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        setPlayingWordCalls += sessionId to wordId
        // 语句 1：现播放词回 PENDING（Next 跳过留在组内循环）
        list.replaceAll {
            if (it.status == SessionWordStatus.PLAYING) it.copy(status = SessionWordStatus.PENDING) else it
        }
        // 语句 2：目标词落 PLAYING（MASTERED 拒绝 = 事务回滚语义）
        val index = list.indexOfFirst { it.wordId == wordId }
        if (index < 0) throw RepositoryValidationException("会话词不存在：sessionId=$sessionId, wordId=$wordId")
        if (list[index].status == SessionWordStatus.MASTERED) {
            throw RepositoryValidationException("已掌握词不能置为播放中：sessionId=$sessionId, wordId=$wordId")
        }
        list[index] = list[index].copy(status = SessionWordStatus.PLAYING, masteredAt = null)
    }

    override suspend fun countUnmasteredEntries(wordBookId: Long): Int =
        unmasteredEntryCounts[wordBookId] ?: 0

    override suspend fun terminateSessionIfActive(sessionId: Long, status: SessionStatus): LearningSession {
        if (status == SessionStatus.ACTIVE) throw RepositoryValidationException("终态不能为 ACTIVE")
        val existing = sessions[sessionId]
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        return if (existing.status == SessionStatus.ACTIVE) {
            terminateSessionCalls += sessionId to status
            existing.copy(status = status, endedAt = Instant.fromEpochMilliseconds(0))
                .also { sessions[sessionId] = it }
        } else {
            existing // 已终态：不写入不刷新 endedAt（幂等，首次终态时刻保持）
        }
    }

    override suspend fun recoverSessionIntegrity(sessionId: Long): SessionRecoveryResult {
        val session = sessions[sessionId]
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        if (session.status != SessionStatus.ACTIVE) {
            throw RepositoryValidationException(
                "恢复完整性检查只针对 ACTIVE 会话：sessionId=$sessionId",
            )
        }
        if (session.wordBookId in deletedBooks) {
            // §10-8 安全 ABANDON（同事务语义：检查与落库原子，失败即回滚）
            updateSessionStatus(sessionId, SessionStatus.ABANDONED)
            return SessionRecoveryResult.BookDeleted
        }
        // §10-9 剔除悬挂词（任何状态），placement 原样保留；WordMastery 不在触碰范围
        bookEntryWordIds[session.wordBookId]?.let { entries ->
            words[sessionId]?.removeAll { it.wordId !in entries }
        }
        return SessionRecoveryResult.Recovered(SessionSnapshot(session, orderedWords(sessionId)))
    }
}

/** 设置端口 Fake：默认值 = 内置默认（groupSize 10、六开关全开，DATABASE_SCHEMA §2.11）。 */
internal class FakeLearningSettingsRepository(
    var groupSize: Int = LearningSettingsRepository.DEFAULT_GROUP_SIZE,
    var playbackToggles: PlaybackToggles = PlaybackToggles.DEFAULT,
    var commandWindowMs: Long = LearningSettingsRepository.DEFAULT_COMMAND_WINDOW_MS,
    var ttsRate: Float = LearningSettingsRepository.DEFAULT_TTS_RATE,
    var ttsPitch: Float = LearningSettingsRepository.DEFAULT_TTS_PITCH,
) : LearningSettingsRepository {
    override suspend fun getGroupSize(): Int = groupSize
    override suspend fun getPlaybackToggles(): PlaybackToggles = playbackToggles
    override suspend fun getCommandWindowMs(): Long = commandWindowMs
    override suspend fun getTtsRate(): Float = ttsRate
    override suspend fun getTtsPitch(): Float = ttsPitch
}

/**
 * 生词本仓储 Fake（Phase 3 Step 5D）：learning 层测试只驱动派生三方法
 * （getWordBookName / countBooksWithName / deriveWordBook）——Phase 2 管理方法为良性桩
 * （对应行为由 Phase 2 的 jvmTest 集成测试锁定）。真实 Q5 事务原子性/重启持久化
 * 见 jvmTest LearningEngineDerivationIntegrationTest。
 */
internal class FakeWordBookRepository : WordBookRepository {

    /** wordBookId → name（getWordBookName 数据源；未登记 = 本不存在）。 */
    val bookNames = mutableMapOf<Long, String>()

    /** deriveWordBook 调用记录：(parentWordBookId, sourceSessionId, name)。 */
    val deriveCalls = mutableListOf<Triple<Long, Long, String>>()

    private var nextBookId = 1_000L

    override fun observeWordBooks(): Flow<List<WordBookSummary>> = MutableStateFlow(emptyList())

    override suspend fun getWordBooks(): List<WordBookSummary> = emptyList()

    override suspend fun createWordBook(name: String): Long {
        val id = nextBookId++
        bookNames[id] = name
        return id
    }

    override suspend fun renameWordBook(wordBookId: Long, newName: String) {
        bookNames[wordBookId] = newName
    }

    override suspend fun deleteWordBook(wordBookId: Long) {
        bookNames.remove(wordBookId)
    }

    override suspend fun getWordBookWords(wordBookId: Long): List<WordBookWord> = emptyList()

    override suspend fun removeWordFromWordBook(wordBookId: Long, wordId: Long) = Unit

    override suspend fun saveWordToBooks(request: SaveWordRequest) = Unit

    override suspend fun getWordBookName(wordBookId: Long): String? = bookNames[wordBookId]

    override suspend fun countBooksWithName(name: String): Int = bookNames.values.count { it == name }

    /** Case 3 裁决模拟（2026-09-04）：true → deriveWordBook 返回 null（交集为空不建空本）。 */
    var emptyIntersection: Boolean = false

    override suspend fun deriveWordBook(
        parentWordBookId: Long,
        sourceSessionId: Long,
        name: String,
        createdAt: Instant,
    ): Long? {
        if (emptyIntersection) return null
        val id = nextBookId++
        bookNames[id] = name
        deriveCalls += Triple(parentWordBookId, sourceSessionId, name)
        return id
    }
}
