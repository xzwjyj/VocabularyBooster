package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.repository.RepositoryValidationException

/**
 * 保存流校验器（FR-5 应用层校验，DOMAIN_MODEL §2.6b 不变量）：
 * 词/本必须存在；释义必须属于该词；例句必须属于所选释义。
 */
internal class SaveRequestValidator(private val database: VocabularyDatabase) {

    fun validate(request: SaveWordRequest) {
        requireWordExists(request.wordId)
        request.wordBookIds.forEach { requireBookExists(it) }
        validateSelections(request)
    }

    private fun requireWordExists(wordId: Long) {
        if (database.wordQueries.selectById(wordId).executeAsOneOrNull() == null) {
            throw RepositoryValidationException("词不存在：wordId=$wordId")
        }
    }

    private fun requireBookExists(wordBookId: Long) {
        if (database.wordBookQueries.selectWordBookById(wordBookId).executeAsOneOrNull() == null) {
            throw RepositoryValidationException("生词本不存在：wordBookId=$wordBookId")
        }
    }

    private fun validateSelections(request: SaveWordRequest) {
        val entries = database.definitionEntryQueries
            .selectDefinitionsForWord(request.wordId)
            .executeAsList()
        val entryIds = entries.map { it.definitionEntryId }.toSet()
        val exampleIdsByEntry = entries.associate { entry ->
            entry.definitionEntryId to database.exampleQueries
                .selectExamplesForEntry(entry.definitionEntryId)
                .executeAsList()
                .map { it.exampleId }
                .toSet()
        }
        request.selections.forEach { selection ->
            if (selection.definitionEntryId !in entryIds) {
                throw RepositoryValidationException(
                    "释义 ${selection.definitionEntryId} 不属于词 ${request.wordId}",
                )
            }
            val legalExamples = exampleIdsByEntry[selection.definitionEntryId].orEmpty()
            selection.exampleIds.forEach { exampleId ->
                if (exampleId !in legalExamples) {
                    throw RepositoryValidationException(
                        "例句 $exampleId 不属于释义 ${selection.definitionEntryId}",
                    )
                }
            }
        }
    }
}
