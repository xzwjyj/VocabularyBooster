package com.vocabularybooster.learning

import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * StudyQueueBuilder（LE spec §3，TC-LE-01 建队侧地基）：
 * 本不存在沿用 domain 契约；空本 / 全掌握拒绝原因与规格一致；
 * 队列顺序 = Q2 entryOrder ASC 原样，不按 wordId 重排；同输入同输出。
 */
class StudyQueueBuilderTest {

    private fun snapshot(
        count: Int,
        queue: List<StudyQueueWord>,
        exists: Boolean = true,
        bookId: Long = 1L,
    ): WordBookStudySnapshot = WordBookStudySnapshot(bookId, exists, count, queue)

    private fun q(wordId: Long, entryOrder: Int) = StudyQueueWord(wordId, entryOrder)

    @Test
    fun nonexistentBookThrowsPerExistingDomainContract() {
        // 沿用 domain 既有契约（WordBookRepository / LearningSessionRepository 同款），不新增错误类型
        val e = assertFailsWith<RepositoryValidationException> {
            StudyQueueBuilder.build(snapshot(count = 0, queue = emptyList(), exists = false, bookId = 42L))
        }
        assertEquals("生词本不存在：wordBookId=42", e.message)
    }

    @Test
    fun emptyBookIsRejectedWithEmptyBook() {
        val result = StudyQueueBuilder.build(snapshot(count = 0, queue = emptyList()))
        assertEquals(StudyQueueResult.Rejected(StudyQueueResult.Reason.EMPTY_BOOK), result)
    }

    @Test
    fun allMasteredBookIsRejectedWithAllMastered() {
        // 有词（count=5）但 Q2 队列为空 = 全部已掌握 → 引导查看勋章，非 EMPTY_BOOK
        val result = StudyQueueBuilder.build(snapshot(count = 5, queue = emptyList()))
        assertEquals(StudyQueueResult.Rejected(StudyQueueResult.Reason.ALL_MASTERED), result)
    }

    @Test
    fun queueFollowsQ2EntryOrderNotWordIdOrder() {
        // Q2 已按 entryOrder ASC 返回；wordId 乱序（90,10,50 非升序）不得触发任何按 wordId 的重排
        val queue = listOf(
            q(wordId = 90, entryOrder = 0),
            q(wordId = 10, entryOrder = 1),
            q(wordId = 50, entryOrder = 2),
        )
        val result = StudyQueueBuilder.build(snapshot(count = 3, queue = queue)) as StudyQueueResult.Queue
        assertEquals(listOf(90L, 10L, 50L), result.words.map { it.wordId })
        assertEquals(listOf(0, 1, 2), result.words.map { it.entryOrder })
    }

    @Test
    fun sameInputAlwaysProducesSameResult() {
        val snap = snapshot(count = 3, queue = listOf(q(3, 0), q(1, 1), q(2, 2)))
        assertEquals(StudyQueueBuilder.build(snap), StudyQueueBuilder.build(snap))
    }

    @Test
    fun resultQueueIsInsulatedFromLaterInputMutation() {
        val queue = mutableListOf(q(3, 0), q(1, 1))
        val result = StudyQueueBuilder.build(snapshot(count = 2, queue = queue)) as StudyQueueResult.Queue
        queue.add(q(9, 2))
        assertEquals(2, result.words.size) // 防御性拷贝：产出后输入变更不影响队列
    }

    @Test
    fun inconsistentSnapshotIsRejected() {
        // 快照自一致性（调用方缺陷 fail-fast）：未掌握数 > 总数 / 负总数
        assertFailsWith<IllegalArgumentException> {
            StudyQueueBuilder.build(snapshot(count = 1, queue = listOf(q(1, 0), q(2, 1))))
        }
        assertFailsWith<IllegalArgumentException> {
            StudyQueueBuilder.build(snapshot(count = -1, queue = emptyList()))
        }
    }
}
