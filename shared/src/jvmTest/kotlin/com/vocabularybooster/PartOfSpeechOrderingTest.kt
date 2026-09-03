package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.domain.model.groupedByPartOfSpeech
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * REQUIRED DATA TEST（Phase 2 批准文本指定）：故意乱序入库 →
 * 输出必须 Verb#1, Verb#2, Verb#3, Noun#1, Noun#2, Adjective#1（TC-DM-01/02）。
 */
class PartOfSpeechOrderingTest {

    @Test
    fun shuffledInsertionStillRendersInCanonicalOrder() = runTest {
        val db = TestDb.inMemory()
        val now = 1_760_000_000_000L
        db.database.wordQueries.insertWord("shuffle", "shuffle", null, null, null, now, now)
        val wordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()

        // 插入顺序（故意乱序）：Noun#2, Verb#3, Noun#1, Verb#1, Adjective#1, Verb#2
        val insertionOrder = listOf(
            "noun" to 2,
            "verb" to 3,
            "noun" to 1,
            "verb" to 1,
            "adjective" to 1,
            "verb" to 2,
        )
        val posOrder = mapOf("verb" to 0L, "noun" to 1L, "adjective" to 2L)
        insertionOrder.forEach { (pos, defOrder) ->
            db.database.definitionEntryQueries.insertDefinitionEntry(
                wordId, pos, posOrder.getValue(pos), defOrder.toLong(),
                "$pos EN #$defOrder", "$pos 中文 #$defOrder",
            )
        }

        val detail = SqlDelightWordRepository(db.database, DispatchersForTest).lookup("shuffle")!!

        // (partOfSpeech, definitionOrder) 全序断言
        assertEquals(
            listOf(
                "verb" to 1, "verb" to 2, "verb" to 3,
                "noun" to 1, "noun" to 2,
                "adjective" to 1,
            ),
            detail.entries.map { it.partOfSpeech to it.definitionOrder },
        )

        // I-1/I-2 连续性：分组为 [verb×3, noun×2, adjective×1]，组内无交叉
        val groups = detail.groupedByPartOfSpeech()
        assertEquals(listOf("verb", "noun", "adjective"), groups.map { it.partOfSpeech })
        assertEquals(listOf(3, 2, 1), groups.map { it.definitions.size })
        assertEquals(
            listOf(1, 2, 3),
            groups[0].definitions.map { it.entry.definitionOrder },
        )
    }
}
