package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WordBookDeriver（LE spec §8 分支 B / DOMAIN_MODEL §10，Phase 3 Step 5D）：
 * 分支 A 零掌握不派生、分支 B 派生命名/血缘/母本缺失异常。
 * 命名格式由 jvmTest 集成测试覆盖；本测试只验证核心裁决逻辑。
 */
class WordBookDeriverTest {

    @Suppress("VariableNaming") // test naming follows test conventions
    // 固定时间戳（任何值均可，测试不依赖具体格式）
    private val FIXED_INSTANT = Instant.fromEpochMilliseconds(1_760_000_000_000L)
    private fun fixedClock() = object : Clock { override fun now(): Instant = FIXED_INSTANT }

    private fun deriver(wordBooks: FakeWordBookRepository) = WordBookDeriver(wordBooks, fixedClock(), TimeZone.UTC)

    private fun session(sessionId: Long = 5L, wordBookId: Long = 1L) = LearningSession(
        sessionId = sessionId,
        wordBookId = wordBookId,
        status = SessionStatus.ABANDONED,
        groupSize = 10,
        startedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun word(wordId: Long, order: Int, status: SessionWordStatus) = SessionWord(
        sessionId = 5L,
        wordId = wordId,
        groupIndex = order / 10,
        orderInGroup = order % 10,
        status = status,
    )

    // —— 分支 A / B 裁决 ——

    @Test
    fun deriveZeroMasteredReturnsNullWithoutWriting() = kotlinx.coroutines.test.runTest {
        val wordBooks = FakeWordBookRepository().apply { bookNames[1L] = "母本" }
        val words = listOf(
            word(101L, 0, SessionWordStatus.PENDING),
            word(102L, 1, SessionWordStatus.PLAYING),
        )

        val result = deriver(wordBooks).derive(session(), words)

        assertEquals(null, result) // 分支 A：零掌握不派生
        assertTrue(wordBooks.deriveCalls.isEmpty())
    }

    @Test
    fun derivePartialMasteryCreatesDerivedWithLineage() = kotlinx.coroutines.test.runTest {
        val wordBooks = FakeWordBookRepository().apply { bookNames[1L] = "母本" }
        val words = listOf(
            word(101L, 0, SessionWordStatus.MASTERED),
            word(102L, 1, SessionWordStatus.PENDING),
            word(103L, 2, SessionWordStatus.PENDING),
        )

        val result = deriver(wordBooks).derive(session(sessionId = 7L, wordBookId = 1L), words)

        assertNotNull(result)
        val (parent, sourceSession, _) = wordBooks.deriveCalls.single()
        assertEquals(1L to 7L, parent to sourceSession) // 血缘：母本 + 来源会话
    }

    @Test
    fun deriveMissingMotherThrows() = kotlinx.coroutines.test.runTest {
        val wordBooks = FakeWordBookRepository() // 未登记母本名
        val words = listOf(word(101L, 0, SessionWordStatus.MASTERED))

        assertFailsWith<RepositoryValidationException> {
            deriver(wordBooks).derive(session(), words)
        }
        assertTrue(wordBooks.deriveCalls.isEmpty())
    }

    @Test
    fun derivePropagatesEmptyIntersectionAsNull() = kotlinx.coroutines.test.runTest {
        // Case 3 裁决（2026-09-04，交集语义）：仓储交集守卫返回 null → 派生器透传不建空本
        //（真实 JDBC 路径的两个 Case 3 场景见 jvmTest LearningEngineDerivationIntegrationTest）
        val wordBooks = FakeWordBookRepository().apply {
            bookNames[1L] = "母本"
            emptyIntersection = true
        }
        val words = listOf(
            word(101L, 0, SessionWordStatus.MASTERED),
            word(102L, 1, SessionWordStatus.PENDING),
        )

        assertEquals(null, deriver(wordBooks).derive(session(), words)) // 交集空 → null
        assertTrue(wordBooks.deriveCalls.isEmpty()) // 无派生本创建
    }

    // —— 命名格式（2026-09-04 裁决配套恢复：精确断言；Instant 由墙上时间反推，不做 epoch 心算）——

    @Test
    fun formatUsesUtcWallTime() {
        val at = LocalDateTime(2025, 10, 9, 8, 53).toInstant(TimeZone.UTC)
        assertEquals(
            "母本 2025-10-09 08:53",
            deriver(FakeWordBookRepository()).formatDerivedName("母本", at),
        )
    }

    @Test
    fun formatUsesInjectedLocalTimeZone() {
        // 同一 Instant（UTC 08:53）在 Asia/Shanghai 墙上时间 = 16:53：证明格式化走注入时区
        val at = LocalDateTime(2025, 10, 9, 8, 53).toInstant(TimeZone.UTC)
        val shanghai = WordBookDeriver(FakeWordBookRepository(), fixedClock(), TimeZone.of("Asia/Shanghai"))
        assertEquals("母本 2025-10-09 16:53", shanghai.formatDerivedName("母本", at))
    }
}
