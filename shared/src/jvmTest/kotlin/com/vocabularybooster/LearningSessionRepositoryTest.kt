package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordPlacement
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.ActiveSessionExistsException
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LearningSessionRepository（Phase 3 Step 1，FR-6/FR-11）：
 * 会话创建原子性、ACTIVE 唯一不变量、状态更新、分组/单词查询、
 * 崩溃恢复地基（close/reopen 快照一致性）。
 * 学习引擎规则（队列构建/分组/掌握裁决/派生）不在此层，后续 Step 以本套为地基。
 */
class LearningSessionRepositoryTest {

    private fun newRepo(db: TestDb, clock: FixedClock = FixedClock()): LearningSessionRepository =
        SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)

    /** 建本 + N 词（单事务，rowid 钉连接；返回 (bookId, wordIds)，entryOrder 即列表序）。label 使同库多本词文本不撞唯一索引。 */
    private fun TestDb.seedBookWithWords(wordCount: Int, label: String = "s"): Pair<Long, List<Long>> {
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordBookQueries.insertOriginalWordBook("$label-book", null, now, now)
            bookId = database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            repeat(wordCount) { i ->
                database.wordQueries.insertWord("$label-$i", "$label-$i", null, null, null, now, now)
                wordIds += database.wordQueries.selectLastInsertRowId().executeAsOne()
            }
        }
        return bookId to wordIds
    }

    /** GroupSplitter 语义的放置表（LE spec §4：groupIndex = 位置/groupSize，orderInGroup = 位置%groupSize）。 */
    private fun placements(wordIds: List<Long>, groupSize: Int): List<SessionWordPlacement> =
        wordIds.mapIndexed { index, wordId ->
            SessionWordPlacement(wordId = wordId, groupIndex = index / groupSize, orderInGroup = index % groupSize)
        }

    @Test
    fun createSessionMaterializesQueueDeterministically() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val repo = newRepo(db, clock)
        val (bookId, wordIds) = db.seedBookWithWords(25)

        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(wordIds, 10))

        val active = repo.getActiveSession()
        assertNotNull(active)
        assertEquals(sessionId, active.sessionId)
        assertEquals(bookId, active.wordBookId)
        assertEquals(SessionStatus.ACTIVE, active.status)
        assertEquals(10, active.groupSize)
        assertEquals(clock.now(), active.startedAt) // FixedClock 注入，创建时刻可断言
        assertNull(active.endedAt)
        assertEquals(active, repo.getSession(sessionId))
        assertNull(repo.getSession(sessionId + 1))

        // 25 词 → 3 组（10/10/5），全 PENDING，(groupIndex, orderInGroup) 全序确定性
        val words = repo.getSessionWords(sessionId)
        assertEquals(25, words.size)
        assertEquals(
            placements(wordIds, 10).map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
            words.map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
        )
        assertTrue(words.all { it.status == SessionWordStatus.PENDING && it.masteredAt == null })
        assertEquals(listOf(10, 10, 5), words.groupBy { it.groupIndex }.toSortedMap().values.map { it.size })
    }

    @Test
    fun createSessionRejectsSecondActiveAndLeavesNoPartialState() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookA, wordsA) = db.seedBookWithWords(10, label = "a")
        val (bookB, wordsB) = db.seedBookWithWords(5, label = "b")
        val sessionA = repo.createSession(bookA, groupSize = 10, words = placements(wordsA, 10))

        // ACTIVE 会话 A 存在 → 创建 B 被拒，异常携带 A 的 ID（引擎映射 Rejected(ACTIVE_SESSION_EXISTS)）
        val e = assertFailsWith<ActiveSessionExistsException> {
            repo.createSession(bookB, groupSize = 3, words = placements(wordsB, 3))
        }
        assertEquals(sessionA, e.activeSessionId)

        // 无半成品：B 的会话行与队列行都不存在，ACTIVE 仍只有 A
        assertNull(repo.getSession(sessionA + 1))
        assertTrue(repo.getSessionWords(sessionA + 1).isEmpty())
        assertEquals(sessionA, repo.getActiveSession()?.sessionId)
        assertEquals(10, repo.getSessionWords(sessionA).size)

        // A 终止（ABANDONED）后不变量重新武装，可再建
        repo.updateSessionStatus(sessionA, SessionStatus.ABANDONED)
        assertNull(repo.getActiveSession())
        val sessionC = repo.createSession(bookB, groupSize = 3, words = placements(wordsB, 3))
        assertEquals(sessionC, repo.getActiveSession()?.sessionId)
        assertEquals(5, repo.getSessionWords(sessionC).size)
    }

    @Test
    fun createSessionRejectsInvalidInput() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookId, wordIds) = db.seedBookWithWords(3)

        assertFailsWith<RepositoryValidationException> {
            repo.createSession(wordBookId = 999L, groupSize = 10, words = placements(wordIds, 10))
        }
        assertFailsWith<RepositoryValidationException> {
            repo.createSession(bookId, groupSize = 0, words = placements(wordIds, 10))
        }
        assertFailsWith<RepositoryValidationException> {
            repo.createSession(bookId, groupSize = 10, words = emptyList())
        }
        // 拒绝后无任何写入
        assertNull(repo.getActiveSession())
        assertTrue(repo.getSessionWords(1L).isEmpty())
    }

    @Test
    fun createSessionMaterializationIsAtomic() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookId, words) = db.seedBookWithWords(3)

        // 同词重复放置 → 第二条 INSERT 违反 PK（sessionId, wordId），发生在会话行已插入之后。
        // 违反类型是驱动层异常（JDBC 为 SQLException 系），引擎后续按 ARCHITECTURE §6 包装，
        // 此处只断言「失败发生 + 全量回滚」，不与具体驱动异常类型耦合
        val duplicated = listOf(
            SessionWordPlacement(words[0], groupIndex = 0, orderInGroup = 0),
            SessionWordPlacement(words[0], groupIndex = 0, orderInGroup = 1),
        )
        val outcome = runCatching { repo.createSession(bookId, groupSize = 10, words = duplicated) }
        assertTrue(outcome.isFailure)

        // 全量回滚：ACTIVE 会话行（事务内已插入）与先成功的队列行一并消失，无半成品
        assertNull(repo.getActiveSession())
        assertNull(repo.getSession(1L))
        assertTrue(repo.getSessionWords(1L).isEmpty())
    }

    @Test
    fun sessionStatusUpdateWritesEndedAtAndGuardsActive() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val repo = newRepo(db, clock)
        val (bookId, words) = db.seedBookWithWords(2)
        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(words, 10))

        clock.advanceMillis(5_000)
        repo.updateSessionStatus(sessionId, SessionStatus.ABANDONED)

        val session = repo.getSession(sessionId)
        assertNotNull(session)
        assertEquals(SessionStatus.ABANDONED, session.status)
        assertEquals(clock.now(), session.endedAt) // endedAt = 注入 Clock 终止时刻
        assertNull(repo.getActiveSession())

        // ACTIVE 只能由创建会产生；会话不存在时拒绝
        assertFailsWith<RepositoryValidationException> {
            repo.updateSessionStatus(sessionId, SessionStatus.ACTIVE)
        }
        assertFailsWith<RepositoryValidationException> {
            repo.updateSessionStatus(999L, SessionStatus.ABANDONED)
        }
    }

    @Test
    fun sessionWordQueriesReturnOrderedSubsets() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookId, wordIds) = db.seedBookWithWords(25)
        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(wordIds, 10))

        // 按组查询：组 1 = 词 10..19，orderInGroup 0..9 升序；空组返回空列表
        val group1 = repo.getSessionWordsByGroup(sessionId, groupIndex = 1)
        assertEquals(wordIds.slice(10 until 20), group1.map { it.wordId })
        assertEquals((0 until 10).toList(), group1.map { it.orderInGroup })
        assertTrue(repo.getSessionWordsByGroup(sessionId, groupIndex = 9).isEmpty())
        assertTrue(repo.getSessionWordsByGroup(999L, groupIndex = 0).isEmpty())

        // 单词查询：命中与未命中
        val hit = repo.getSessionWord(sessionId, wordIds[23])
        assertNotNull(hit)
        assertEquals(2, hit.groupIndex)
        assertEquals(3, hit.orderInGroup)
        assertNull(repo.getSessionWord(sessionId, 999L))
    }

    @Test
    fun updateSessionWordStatusTracksMasteredAt() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val repo = newRepo(db, clock)
        val (bookId, words) = db.seedBookWithWords(2)
        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(words, 10))

        // PENDING → PLAYING：无 masteredAt
        repo.updateSessionWordStatus(sessionId, words[0], SessionWordStatus.PLAYING)
        var word = repo.getSessionWord(sessionId, words[0])
        assertNotNull(word)
        assertEquals(SessionWordStatus.PLAYING, word.status)
        assertNull(word.masteredAt)

        // → MASTERED：masteredAt = 注入 Clock 时刻
        clock.advanceMillis(1_234)
        repo.updateSessionWordStatus(sessionId, words[0], SessionWordStatus.MASTERED)
        word = repo.getSessionWord(sessionId, words[0])
        assertNotNull(word)
        assertEquals(SessionWordStatus.MASTERED, word.status)
        assertEquals(clock.now(), word.masteredAt)

        // SKIPPED：schema 预留状态可经底层 update 表达并可读回（v1 无业务入口产生，DOMAIN_MODEL §8.3）
        repo.updateSessionWordStatus(sessionId, words[1], SessionWordStatus.SKIPPED)
        val skipped = repo.getSessionWord(sessionId, words[1])
        assertNotNull(skipped)
        assertEquals(SessionWordStatus.SKIPPED, skipped.status)
        assertNull(skipped.masteredAt)

        // 行不存在拒绝
        assertFailsWith<RepositoryValidationException> {
            repo.updateSessionWordStatus(sessionId, 999L, SessionWordStatus.PLAYING)
        }
    }

    @Test
    fun recoverySnapshotSurvivesCloseAndReopen() = runTest {
        // 崩溃恢复地基（NFR-3）：物化 → 部分推进（含 SKIPPED 读路径）→ close → reopen → 快照逐字段一致
        val db = TestDb.file()
        var expected: SessionSnapshot? = null
        try {
            val clock = FixedClock()
            val repo = newRepo(db, clock)
            val (bookId, wordIds) = db.seedBookWithWords(7) // groupSize 3 → 组 0:3 / 1:3 / 2:1
            val sessionId = repo.createSession(bookId, groupSize = 3, words = placements(wordIds, 3))

            clock.advanceMillis(9_000)
            repo.updateSessionWordStatus(sessionId, wordIds[0], SessionWordStatus.PLAYING)
            clock.advanceMillis(1_000)
            repo.updateSessionWordStatus(sessionId, wordIds[0], SessionWordStatus.MASTERED)
            repo.updateSessionWordStatus(sessionId, wordIds[4], SessionWordStatus.SKIPPED)

            expected = repo.getSessionWithWords(sessionId)
            assertNotNull(expected)
            assertEquals(7, expected.words.size)
            assertEquals(
                Instant.fromEpochMilliseconds(1_760_000_010_000L),
                expected.words.first { it.wordId == wordIds[0] }.masteredAt,
            )
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val repo2 = newRepo(reopened)

            // 恢复读取确定性：会话（含 groupSize 固化值）+ 全部队列行（group/order/状态/masteredAt）一致
            assertEquals(expected, repo2.getSessionWithWords(expected!!.session.sessionId))
            assertEquals(expected.session, repo2.getActiveSession())

            // 不完整/未知数据：会话不存在 → null；队列读取不抛异常、不产生数据
            assertNull(repo2.getSessionWithWords(999L))
            assertNull(repo2.getSession(999L))
            assertTrue(repo2.getSessionWords(999L).isEmpty())
        } finally {
            reopened.close()
        }
    }

    // —— Phase 3 Step 3：markSessionWordMastered（LE spec §6 事务，真实 JDBC 驱动）——

    @Test
    fun markSessionWordMasteredIsTransactionalAndIdempotent() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val repo = newRepo(db, clock)
        val (bookId, wordIds) = db.seedBookWithWords(3)
        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(wordIds, 10))

        // 首次：PENDING → MASTERED，SessionWord 与 WordMastery 同事务写入（返回 true）
        clock.advanceMillis(2_000)
        assertTrue(repo.markSessionWordMastered(sessionId, wordIds[0], clock.now()))
        val marked = repo.getSessionWord(sessionId, wordIds[0])
        assertNotNull(marked)
        assertEquals(SessionWordStatus.MASTERED, marked.status)
        assertEquals(clock.now(), marked.masteredAt)
        val masteryRow = db.database.wordMasteryQueries.isMastered(bookId, wordIds[0]).executeAsOne()
        assertEquals(clock.now().toEpochMilliseconds(), masteryRow.masteredAt)

        // 重复 mark：条件 UPDATE 0 行受影响 → false；masteredAt 不刷新；WordMastery 不重复
        clock.advanceMillis(60_000)
        assertFalse(repo.markSessionWordMastered(sessionId, wordIds[0], clock.now()))
        assertEquals(marked, repo.getSessionWord(sessionId, wordIds[0]))
        assertEquals(1L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())

        // PLAYING → MASTERED 同走条件更新（spec §8.3）
        repo.updateSessionWordStatus(sessionId, wordIds[1], SessionWordStatus.PLAYING)
        assertTrue(repo.markSessionWordMastered(sessionId, wordIds[1], clock.now()))
        assertEquals(SessionWordStatus.MASTERED, repo.getSessionWord(sessionId, wordIds[1])?.status)
    }

    @Test
    fun markSessionWordMasteredRejectsWordNotInSession() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookId, wordIds) = db.seedBookWithWords(2)
        val sessionId = repo.createSession(bookId, groupSize = 10, words = placements(wordIds, 10))

        assertFailsWith<RepositoryValidationException> {
            repo.markSessionWordMastered(sessionId, wordId = 999L, masteredAt = FixedClock().now())
        }
        // 拒绝后零写入：无掌握行、队列状态原样
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
        assertTrue(repo.getSessionWords(sessionId).all { it.status == SessionWordStatus.PENDING })
    }

    @Test
    fun markSessionWordMasteredRollsBackMidTransactionFailure() = runTest {
        // 真实驱动的半状态防护：JDBC 测试驱动 FK 关闭，可构造孤儿会话词（无会话行）——
        // 条件 UPDATE 成功（1 行）后取会话行失败 → 整个事务回滚：UPDATE 不留痕、WordMastery 不落行
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        val (bookId, wordIds) = db.seedBookWithWords(1)
        db.database.sessionWordQueries.insertSessionWord(
            sessionId = 999L,
            wordId = wordIds[0],
            groupIndex = 0,
            orderInGroup = 0,
            status = "PENDING",
        )

        assertFailsWith<RepositoryValidationException> {
            repo.markSessionWordMastered(999L, wordIds[0], FixedClock().now())
        }

        val orphan = db.database.sessionWordQueries.selectSessionWord(999L, wordIds[0]).executeAsOne()
        assertEquals("PENDING", orphan.status) // UPDATE 已回滚，非半完成状态
        assertNull(orphan.masteredAt)
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
    }
}
