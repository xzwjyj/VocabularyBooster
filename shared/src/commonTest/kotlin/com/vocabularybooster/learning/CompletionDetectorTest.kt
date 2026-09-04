package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CompletionDetector（LE spec §7，TC-LE-05/06 判据地基）：纯函数 input → Boolean。
 * 组完成 = 组内无未掌握词；会话完成 = 队列全 MASTERED；书完成 = Q3 计数为 0。
 */
class CompletionDetectorTest {

    private fun sw(
        wordId: Long,
        groupIndex: Int = 0,
        orderInGroup: Int = 0,
        status: SessionWordStatus = SessionWordStatus.MASTERED,
    ) = SessionWord(
        sessionId = 1L,
        wordId = wordId,
        groupIndex = groupIndex,
        orderInGroup = orderInGroup,
        status = status,
    )

    @Test
    fun unmasteredWordIsIncompleteEverywhere() {
        listOf(SessionWordStatus.PENDING, SessionWordStatus.PLAYING).forEach { status ->
            val words = listOf(sw(wordId = 1, status = status))
            assertFalse(CompletionDetector.isGroupComplete(words))
            assertFalse(CompletionDetector.isSessionComplete(words))
        }
    }

    @Test
    fun singleMasteredWordIsComplete() {
        val words = listOf(sw(wordId = 1))
        assertTrue(CompletionDetector.isGroupComplete(words))
        assertTrue(CompletionDetector.isSessionComplete(words))
    }

    @Test
    fun fullyMasteredGroupIsComplete() {
        assertTrue(
            CompletionDetector.isGroupComplete(
                listOf(sw(1, orderInGroup = 0), sw(2, orderInGroup = 1), sw(3, orderInGroup = 2)),
            ),
        )
    }

    @Test
    fun partiallyMasteredGroupIsIncomplete() {
        assertFalse(
            CompletionDetector.isGroupComplete(
                listOf(
                    sw(1, orderInGroup = 0),
                    sw(2, orderInGroup = 1, status = SessionWordStatus.PENDING),
                    sw(3, orderInGroup = 2),
                ),
            ),
        )
    }

    @Test
    fun completedGroupDoesNotImplySessionComplete() {
        // 两组：组 0 全掌握，组 1 有 PENDING → 组 0 true、会话 false
        val group0 = listOf(sw(1, groupIndex = 0, orderInGroup = 0), sw(2, groupIndex = 0, orderInGroup = 1))
        val group1 = listOf(
            sw(3, groupIndex = 1, orderInGroup = 0),
            sw(4, groupIndex = 1, orderInGroup = 1, status = SessionWordStatus.PENDING),
        )
        assertTrue(CompletionDetector.isGroupComplete(group0))
        assertFalse(CompletionDetector.isGroupComplete(group1))
        assertFalse(CompletionDetector.isSessionComplete(group0 + group1))
    }

    @Test
    fun allGroupsMasteredMeansSessionComplete() {
        val words = listOf(
            sw(1, groupIndex = 0, orderInGroup = 0),
            sw(2, groupIndex = 0, orderInGroup = 1),
            sw(3, groupIndex = 1, orderInGroup = 0),
            sw(4, groupIndex = 1, orderInGroup = 1),
        )
        assertTrue(CompletionDetector.isSessionComplete(words))
        assertTrue(CompletionDetector.isGroupComplete(words.filter { it.groupIndex == 0 }))
        assertTrue(CompletionDetector.isGroupComplete(words.filter { it.groupIndex == 1 }))
    }

    @Test
    fun orderDoesNotAffectVerdictAndRepeatsAreStable() {
        val mastered = listOf(sw(1), sw(2), sw(3))
        val reordered = mastered.asReversed() // 判据为全称量词，与输入顺序无关
        assertEquals(CompletionDetector.isGroupComplete(mastered), CompletionDetector.isGroupComplete(reordered))
        assertTrue(CompletionDetector.isGroupComplete(reordered))
        // 重复调用恒定（纯函数无内部状态）
        assertEquals(CompletionDetector.isSessionComplete(mastered), CompletionDetector.isSessionComplete(mastered))
    }

    @Test
    fun skippedCountsAsNotMasteredInV1() {
        // SKIPPED 为 schema 预留状态（DOMAIN_MODEL §8.3）：v1 无业务产生它，
        // 判据按 status == MASTERED 全称量词——出现 SKIPPED 即未完成，不把它当 v1 掌握行为
        assertFalse(CompletionDetector.isGroupComplete(listOf(sw(1), sw(2, status = SessionWordStatus.SKIPPED))))
    }

    @Test
    fun emptyInputsAreNotCompleteAndBookCompletionFollowsQ3() {
        // 空集 → false（规格未定义空集语义；无学习单元不构成完成，避免噪声事件）
        assertFalse(CompletionDetector.isGroupComplete(emptyList()))
        assertFalse(CompletionDetector.isSessionComplete(emptyList()))
        // 书完成 = Q3 未掌握词条数为 0（LE spec §7）
        assertTrue(CompletionDetector.isBookComplete(0))
        assertFalse(CompletionDetector.isBookComplete(5))
        assertFailsWith<IllegalArgumentException> { CompletionDetector.isBookComplete(-1) }
    }
}
