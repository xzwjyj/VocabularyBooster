package com.vocabularybooster

import com.vocabularybooster.domain.model.DefinitionEntry
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.model.groupedByPartOfSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WordDetail 分组不变量（I-1/I-2 连续性；TC-DM-01 纯逻辑部分）。 */
class WordDetailGroupingTest {

    private fun entry(id: Long, pos: String, posOrder: Int, defOrder: Int) =
        DefinitionEntry(
            definitionEntryId = id,
            wordId = 1L,
            partOfSpeech = pos,
            partOfSpeechOrder = posOrder,
            definitionOrder = defOrder,
            meaningEN = "EN $id",
            meaningCN = "中文 $id",
        )

    @Test
    fun consecutiveEntriesGroupByPartOfSpeech() {
        val detail = WordDetail(
            word = Word(wordId = 1L, text = "demo", normalizedText = "demo"),
            entries = listOf(
                entry(1, "verb", 0, 1),
                entry(2, "verb", 0, 2),
                entry(3, "noun", 1, 1),
                entry(4, "adjective", 2, 1),
            ),
            examplesByEntryId = mapOf(1L to emptyList()),
        )

        val groups = detail.groupedByPartOfSpeech()
        assertEquals(listOf("verb", "noun", "adjective"), groups.map { it.partOfSpeech })
        assertEquals(listOf(1L, 2L), groups[0].definitions.map { it.entry.definitionEntryId })
        assertEquals(listOf(3L), groups[1].definitions.map { it.entry.definitionEntryId })
        assertTrue(groups[2].definitions.single().examples.isEmpty())
    }

    @Test
    fun emptyEntriesYieldNoGroups() {
        val detail = WordDetail(
            word = Word(wordId = 1L, text = "empty", normalizedText = "empty"),
            entries = emptyList(),
            examplesByEntryId = emptyMap(),
        )
        assertTrue(detail.groupedByPartOfSpeech().isEmpty())
    }
}
