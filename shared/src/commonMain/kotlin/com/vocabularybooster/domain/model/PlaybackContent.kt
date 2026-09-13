package com.vocabularybooster.domain.model

/**
 * 播放内容装配读模型（Phase 4 Step 1，AUDIO §2 SegmentBuilder 的输入）。
 * 读路径 = Q4 `selectSelectedDefinitions` + Q4b `selectSelectedExamples`
 * （DATABASE_SCHEMA §3，只读选中项，FR-10「只播被选中的释义与例句」）：
 *
 * - [selectedDefinitions] 已按 `(partOfSpeechOrder ASC, definitionOrder ASC)` 排序（I-4）；
 * - [examplesByDefinitionEntryId] 每组已按 `exampleOrder ASC` 排序（I-8）；
 * - 导入词（零释义选择，FR-14/LE §10-3）= 两集合为空的合法形态——空段编排语义
 *   见 AUDIO §2 裁决 L2（空段 → CommandWindow → advance，绝不 MASTERED），不在本模型裁决。
 */
public data class PlaybackContent(
    public val word: Word,
    public val selectedDefinitions: List<DefinitionEntry>,
    public val examplesByDefinitionEntryId: Map<Long, List<Example>>,
)
