package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordDetail

/**
 * 词仓储端口（Phase 2）：API 由领域需要决定，UI 不得直接访问 SQLDelight。
 * 平台无关（NFR-6）；实现见 data 层。
 */
public interface WordRepository {

    /** FR-1：查一个词的完整词条（大小写/首尾空白不敏感）；未收录返回 null。 */
    public suspend fun lookup(text: String): WordDetail?

    /** 前缀搜索（查词 UI 联想）：短词优先，字典序，limit 截断。 */
    public suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Word>

    public companion object {
        public const val DEFAULT_SEARCH_LIMIT: Int = 20
    }
}
