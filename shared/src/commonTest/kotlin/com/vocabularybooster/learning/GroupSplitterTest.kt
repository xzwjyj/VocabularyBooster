package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionWordPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GroupSplitter（LE spec §4，TC-LE-02 分组侧地基）：
 * groupIndex/orderInGroup 均 0 起（DOMAIN_MODEL §2.9）；位置即真相，不按 wordId 重排；
 * 边界 1/9/10/11/20/21/25/100 词、groupSize 1/=N/<N、非法 groupSize、确定性。
 */
class GroupSplitterTest {

    /** entryOrder 升序队列（wordId 与位置一致，仅作默认；乱序用例单独构造）。 */
    private fun queueOfSize(n: Int): List<StudyQueueWord> =
        (0 until n).map { StudyQueueWord(wordId = it.toLong() + 1L, entryOrder = it) }

    /** 分组视图：groupIndex → 组内 orderInGroup 列表（遇序保持升序）。 */
    private fun groupsOf(n: Int, groupSize: Int): Map<Int, List<Int>> =
        GroupSplitter.split(queueOfSize(n), groupSize).groupBy({ it.groupIndex }, { it.orderInGroup })

    @Test
    fun emptyQueueYieldsNoPlacements() {
        assertTrue(GroupSplitter.split(emptyList(), groupSize = 10).isEmpty())
    }

    @Test
    fun singleWordFormsSingleGroup() {
        val placements = GroupSplitter.split(queueOfSize(1), groupSize = 10)
        assertEquals(listOf(SessionWordPlacement(wordId = 1L, groupIndex = 0, orderInGroup = 0)), placements)
    }

    @Test
    fun boundariesAroundGroupSizeHold() {
        // 9 → 1 组(0..8)；10 → 恰满 1 组；11 → 2 组(末组 1)；20 → 2 组恰满；21 → 3 组(末组 1)
        assertEquals(mapOf(0 to (0..8).toList()), groupsOf(9, 10))
        assertEquals(mapOf(0 to (0..9).toList()), groupsOf(10, 10))
        assertEquals(mapOf(0 to (0..9).toList(), 1 to listOf(0)), groupsOf(11, 10))
        assertEquals(mapOf(0 to (0..9).toList(), 1 to (0..9).toList()), groupsOf(20, 10))
        assertEquals(mapOf(0 to (0..9).toList(), 1 to (0..9).toList(), 2 to listOf(0)), groupsOf(21, 10))
    }

    @Test
    fun groupSizeOnePutsEveryWordInItsOwnGroup() {
        val placements = GroupSplitter.split(queueOfSize(5), groupSize = 1)
        assertEquals((0..4).toList(), placements.map { it.groupIndex })
        assertTrue(placements.all { it.orderInGroup == 0 })
    }

    @Test
    fun groupSizeEqualToQueueSizeFormsOneFullGroup() {
        val placements = GroupSplitter.split(queueOfSize(7), groupSize = 7)
        assertEquals(1, placements.map { it.groupIndex }.distinct().size)
        assertEquals((0..6).toList(), placements.map { it.orderInGroup })
    }

    @Test
    fun queueSmallerThanGroupSizeFormsSingleShortGroup() {
        val placements = GroupSplitter.split(queueOfSize(3), groupSize = 10)
        assertEquals(listOf(0, 0, 0), placements.map { it.groupIndex })
        assertEquals(listOf(0, 1, 2), placements.map { it.orderInGroup })
    }

    @Test
    fun invalidGroupSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { GroupSplitter.split(queueOfSize(3), groupSize = 0) }
        assertFailsWith<IllegalArgumentException> { GroupSplitter.split(queueOfSize(3), groupSize = -1) }
    }

    @Test
    fun inputOrderPrevailsOverWordIdOrder() {
        // wordId 乱序（50,10,30）：组与组内序按队列位置固化，不按 wordId 重排
        val queue = listOf(
            StudyQueueWord(wordId = 50, entryOrder = 0),
            StudyQueueWord(wordId = 10, entryOrder = 1),
            StudyQueueWord(wordId = 30, entryOrder = 2),
        )
        val placements = GroupSplitter.split(queue, groupSize = 2)
        assertEquals(listOf(50L, 10L, 30L), placements.map { it.wordId })
        assertEquals(listOf(0, 0, 1), placements.map { it.groupIndex })
        assertEquals(listOf(0, 1, 0), placements.map { it.orderInGroup })
    }

    @Test
    fun twentyFiveWordsSplitIntoTenTenFive() {
        // LE spec §4 同款：25 词 groupSize=10 → 10/10/5
        val byGroup = groupsOf(25, 10)
        assertEquals(listOf(0, 1, 2), byGroup.keys.toList()) // groupIndex 连续 0 起
        assertEquals((0..9).toList(), byGroup.getValue(0))
        assertEquals((0..9).toList(), byGroup.getValue(1))
        assertEquals((0..4).toList(), byGroup.getValue(2))
    }

    @Test
    fun hundredWordsTenGroupsExactCoverage() {
        val queue = queueOfSize(100)
        val placements = GroupSplitter.split(queue, groupSize = 10)

        assertEquals(100, placements.size)
        val byGroup = placements.groupBy { it.groupIndex }
        // 10 组、groupIndex 连续 0..9、每组恰 10 词、组内 orderInGroup 连续 0..9
        assertEquals((0..9).toList(), byGroup.keys.toList())
        (0..9).forEach { g -> assertEquals((0..9).toList(), byGroup.getValue(g).map { it.orderInGroup }) }
        // 无遗漏、无重复：词集合与输入一一对应
        assertEquals(queue.map { it.wordId }.toSet(), placements.map { it.wordId }.toSet())
        assertEquals(100, placements.map { it.wordId }.distinct().size)
        // 确定性：同输入重复调用结果恒等
        assertEquals(placements, GroupSplitter.split(queue, groupSize = 10))
    }
}
