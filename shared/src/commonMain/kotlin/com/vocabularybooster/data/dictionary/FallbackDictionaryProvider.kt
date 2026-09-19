package com.vocabularybooster.data.dictionary

import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord

/**
 * 复合词典源（FR-18，Phase 8.6）：primary（精选种子，含例句）未收录才查
 * fallback（随包全量词典，无例句——例句契约仅精选种子，PROJECT_SPEC FR-3 注记）。
 * 大小写/首尾空白不敏感由各成员实现自理（端口契约）。
 */
public class FallbackDictionaryProvider(
    private val primary: DictionaryProvider,
    private val fallback: DictionaryProvider,
) : DictionaryProvider {

    override suspend fun lookup(text: String): DictionaryWord? =
        primary.lookup(text) ?: fallback.lookup(text)
}
