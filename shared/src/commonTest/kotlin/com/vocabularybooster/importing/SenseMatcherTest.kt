package com.vocabularybooster.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SenseMatcher 纯函数口径（SCR-TXTDICTENRICH，IMPORT_SPEC §3.2，TC-IMP-10 前置）：
 * token 切分/归一（trim + lowercase + 分隔符族）、「相等或互含」命中、中英双侧参与。
 */
class SenseMatcherTest {

    @Test
    fun exactTokenHitIsCaseInsensitive() {
        assertTrue(SenseMatcher.matches("妨碍", "hamper, hinder", "妨碍，阻碍"))
        assertTrue(SenseMatcher.matches("HINDER", "hamper, hinder", "妨碍，阻碍")) // EN 侧 + 大小写归一
        assertTrue(SenseMatcher.matches("basket", "a large basket", "大篮子"))
    }

    @Test
    fun mutualContainmentHits() {
        assertTrue(SenseMatcher.matches("受妨碍", "hampered", "受妨碍的，受阻的")) // 词典 ⊇ 用户 token
        assertTrue(SenseMatcher.matches("篮", "hamper", "洗衣篮")) // 词典 ⊇ 用户 token
        assertTrue(SenseMatcher.matches("hamper", "hampered", "受妨碍的")) // 词典 ⊇ 用户 token（EN 侧）
    }

    @Test
    fun noSharedTokenMisses() {
        assertFalse(SenseMatcher.matches("苹果", "hamper, hinder", "妨碍，阻碍"))
        assertFalse(SenseMatcher.matches("2000", "hamper, hinder", "妨碍，阻碍")) // 纯数字 token 无命中
    }

    @Test
    fun delimitersSplitBothSides() {
        // 分隔符族：，, 、;；/ 与空白——切分后逐 token 比对（不是整串 contains）
        assertTrue(SenseMatcher.matches("阻碍", "hamper; hinder / impede", "妨碍、阻碍；阻止"))
        assertFalse(SenseMatcher.matches("阻碍他人工作", "basket", "篮子")) // 切分后无 token 互通
    }

    @Test
    fun blankOrDelimiterOnlyInputNeverHits() {
        assertFalse(SenseMatcher.matches("", "hamper", "妨碍"))
        assertFalse(SenseMatcher.matches("，, 、;；/", "hamper", "妨碍"))
        assertFalse(SenseMatcher.matches("苹果", "", "")) // 词典侧无 token
    }

    @Test
    fun matchingIndexesReturnsHitSetOnly() {
        val meanings = listOf(
            "hamper, hinder" to "妨碍，阻碍", // 0 verb
            "a large basket" to "（带盖的）大篮子", // 1 noun
            "laundry basket" to "洗衣篮", // 2 noun
        )
        assertEquals(setOf(0), SenseMatcher.matchingIndexes("妨碍", meanings))
        assertEquals(setOf<Int>(), SenseMatcher.matchingIndexes("苹果", meanings))
        assertEquals(setOf(1, 2), SenseMatcher.matchingIndexes("篮", meanings)) // 多命中取并集
    }
}
