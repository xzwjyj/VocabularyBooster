package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordPlacement
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MasteryMarker（LE spec §6，TC-LE-04 地基）：PENDING/PLAYING → MASTERED、
 * 幂等（masteredAt 不刷新）、VOICE=BUTTON 等价、会话/词不存在沿用既有契约、
 * 非 ACTIVE 拒绝。经手写 Fake 端口驱动；真实事务语义见 jvmTest。
 */
class MasteryMarkerTest {

    private val wordBookId = 7L

    private suspend fun FakeLearningSessionRepository.givenActiveSession(
        vararg wordIds: Long,
    ): Long = createSession(
        wordBookId = wordBookId,
        groupSize = 10,
        words = wordIds.mapIndexed { index, id -> SessionWordPlacement(id, groupIndex = 0, orderInGroup = index) },
    )

    @Test
    fun pendingToMasteredPersistsAllThreeStates() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val clock = FixedClock()
        val marker = MasteryMarker(repo, clock)
        val sessionId = repo.givenActiveSession(101L, 102L)

        clock.advanceMillis(1_500)
        val result = marker.markMastered(sessionId, wordId = 101L, source = MasterySource.BUTTON)

        assertEquals(MasteryResult.Marked(clock.now()), result)
        val word = assertNotNull(repo.getSessionWord(sessionId, 101L))
        assertEquals(SessionWordStatus.MASTERED, word.status) // SessionWord → MASTERED
        assertEquals(clock.now(), word.masteredAt) // masteredAt null → 首次时刻
        assertEquals(clock.now().toEpochMilliseconds(), repo.mastery[wordBookId to 101L]) // WordMastery 落行
    }

    @Test
    fun voiceAndButtonAreFullyEquivalent() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val clock = FixedClock()
        val marker = MasteryMarker(repo, clock)
        val sessionId = repo.givenActiveSession(201L, 202L)

        val byVoice = marker.markMastered(sessionId, wordId = 201L, source = MasterySource.VOICE)
        val byButton = marker.markMastered(sessionId, wordId = 202L, source = MasterySource.BUTTON)

        // NFR-8：同一入口同一语义——两来源结果形状与落库行为完全一致
        assertEquals(byVoice, byButton)
        assertEquals(MasteryResult.Marked(clock.now()), byVoice)
        assertEquals(
            listOf(SessionWordStatus.MASTERED, SessionWordStatus.MASTERED),
            repo.getSessionWords(sessionId).map { it.status },
        )
        assertEquals(2, repo.mastery.count { it.key.first == wordBookId })
    }

    @Test
    fun alreadyMasteredIsIdempotentAndKeepsFirstTimestamp() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val clock = FixedClock()
        val marker = MasteryMarker(repo, clock)
        val sessionId = repo.givenActiveSession(301L)

        val first = marker.markMastered(sessionId, wordId = 301L, source = MasterySource.VOICE)
        val firstAt = (first as MasteryResult.Marked).masteredAt
        clock.advanceMillis(60_000)

        val second = marker.markMastered(sessionId, wordId = 301L, source = MasterySource.BUTTON)

        assertEquals(MasteryResult.AlreadyMastered, second) // 幂等结果，非错误（LE spec §6）
        assertEquals(firstAt, repo.getSessionWord(sessionId, 301L)?.masteredAt) // 不刷新
        assertEquals(1, repo.mastery.count { it.key.first == wordBookId }) // 不重复
    }

    @Test
    fun missingSessionThrowsExistingContract() = kotlinx.coroutines.test.runTest {
        val marker = MasteryMarker(FakeLearningSessionRepository(), FixedClock())
        assertFailsWith<RepositoryValidationException> {
            marker.markMastered(sessionId = 999L, wordId = 1L, source = MasterySource.BUTTON)
        }
    }

    @Test
    fun wordNotInSessionThrowsExistingContract() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val marker = MasteryMarker(repo, FixedClock())
        val sessionId = repo.givenActiveSession(401L)

        assertFailsWith<RepositoryValidationException> {
            marker.markMastered(sessionId, wordId = 999L, source = MasterySource.VOICE)
        }
    }

    @Test
    fun nonActiveSessionIsRejectedWithoutSideEffects() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val marker = MasteryMarker(repo, FixedClock())
        val sessionId = repo.givenActiveSession(501L)
        repo.updateSessionStatus(sessionId, SessionStatus.ABANDONED)

        val result = marker.markMastered(sessionId, wordId = 501L, source = MasterySource.BUTTON)

        assertEquals(MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE), result)
        assertEquals(SessionWordStatus.PENDING, repo.getSessionWord(sessionId, 501L)?.status) // 状态未动
        assertTrue(repo.mastery.isEmpty()) // 无 WordMastery 写入
    }

    @Test
    fun playingWordIsMasterable() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository()
        val clock = FixedClock()
        val marker = MasteryMarker(repo, clock)
        val sessionId = repo.givenActiveSession(601L)
        repo.updateSessionWordStatus(sessionId, 601L, SessionWordStatus.PLAYING)

        // spec §8.3 PENDING→PLAYING→MASTERED；§10-4 词刚开始播放即"会了"照常落库
        val result = marker.markMastered(sessionId, wordId = 601L, source = MasterySource.VOICE)
        assertEquals(MasteryResult.Marked(clock.now()), result)
        assertEquals(SessionWordStatus.MASTERED, repo.getSessionWord(sessionId, 601L)?.status)
    }
}
