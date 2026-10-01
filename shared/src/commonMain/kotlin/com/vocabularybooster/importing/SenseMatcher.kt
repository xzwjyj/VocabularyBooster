package com.vocabularybooster.importing

/**
 * 带译文导入行的释义匹配（SCR-TXTDICTENRICH，IMPORT_SPEC §3.2）：用户译文 token 与
 * 词典释义 token 归一后「相等或互含」即命中——纯函数、确定性，JVM 全量可测。
 *
 * token 切分：`，, 、;；/` 与空白（用户译文串、词典 meaningCN/meaningEN 同口径）；
 * 归一：trim + lowercase（英文侧大小写不敏感）；空 token 丢弃。
 */
public object SenseMatcher {

    private val TOKEN_DELIMITER = Regex("[，,、;；/\\s]+")

    /** 单条释义是否被用户译文命中（meaningEN / meaningCN 任一侧）。 */
    public fun matches(translation: String, meaningEN: String, meaningCN: String): Boolean {
        val userTokens = splitTokens(translation)
        val senseTokens = splitTokens(meaningEN) + splitTokens(meaningCN)
        if (userTokens.isEmpty() || senseTokens.isEmpty()) return false // 空 token 侧永不命中
        return userTokens.any { user -> senseTokens.any { sense -> tokenHit(user, sense) } }
    }

    /** 释义列表中命中的下标集合（空 = 零匹配，兜底策略由引擎裁决）。 */
    public fun matchingIndexes(
        translation: String,
        meanings: List<Pair<String, String>>, // (meaningEN, meaningCN)
    ): Set<Int> = meanings.indices
        .filterTo(mutableSetOf()) { matches(translation, meanings[it].first, meanings[it].second) }

    private fun splitTokens(raw: String): List<String> = TOKEN_DELIMITER.split(raw)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    /** 相等或互含（"妨碍" ⊆ "妨碍, 阻碍" 切分后的 token 内含于用户整串等场景）。 */
    private fun tokenHit(user: String, sense: String): Boolean =
        user == sense || user.contains(sense) || sense.contains(user)
}
