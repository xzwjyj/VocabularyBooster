package com.vocabularybooster.domain.model

/**
 * 词文本归一化（FR-1 / DOMAIN_MODEL §2.1）：小写 + 去首尾空白。
 * 查询、去重、种子导入统一走此入口。
 */
public fun String.toNormalizedWordText(): String = trim().lowercase()
