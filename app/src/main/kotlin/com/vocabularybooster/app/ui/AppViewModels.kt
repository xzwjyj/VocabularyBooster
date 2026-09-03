package com.vocabularybooster.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import kotlinx.coroutines.launch

/** 查词（FR-1）：查询 → 结果列表。 */
class LookupViewModel(
    private val wordRepository: WordRepository,
) : ViewModel() {

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<Word>>(emptyList())
        private set
    var searched by mutableStateOf(false)
        private set

    fun onQueryChange(value: String) {
        query = value
        viewModelScope.launch {
            results = if (value.isBlank()) emptyList() else wordRepository.search(value.trim())
            searched = true
        }
    }
}

/** 词条详情（FR-2 排序渲染）+ 保存流（FR-5）。 */
class WordDetailViewModel(
    private val wordRepository: WordRepository,
    private val wordBookRepository: WordBookRepository,
) : ViewModel() {

    var detail by mutableStateOf<WordDetail?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    // —— 保存流状态 ——
    var books by mutableStateOf<List<WordBookSummary>>(emptyList())
        private set
    var selectedBooks by mutableStateOf<Set<Long>>(emptySet())
        private set
    var selectedDefinitions by mutableStateOf<Set<Long>>(emptySet())
        private set
    var selectedExamples by mutableStateOf<Map<Long, Set<Long>>>(emptyMap())
        private set
    var newBookName by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set

    fun load(wordText: String) {
        viewModelScope.launch {
            detail = wordRepository.lookup(wordText)
            loadFailed = detail == null
            books = wordBookRepository.getWordBooks()
        }
    }

    fun toggleBook(wordBookId: Long) {
        selectedBooks = if (wordBookId in selectedBooks) selectedBooks - wordBookId else selectedBooks + wordBookId
    }

    fun toggleDefinition(definitionEntryId: Long) {
        selectedDefinitions =
            if (definitionEntryId in selectedDefinitions) selectedDefinitions - definitionEntryId
            else selectedDefinitions + definitionEntryId
    }

    fun toggleExample(definitionEntryId: Long, exampleId: Long) {
        val current = selectedExamples[definitionEntryId].orEmpty()
        val next = if (exampleId in current) current - exampleId else current + exampleId
        selectedExamples = selectedExamples + (definitionEntryId to next)
    }

    fun onNewBookNameChange(value: String) {
        newBookName = value
    }

    fun createBookAndSelect() {
        val name = newBookName.trim()
        if (name.isEmpty()) {
            message = "生词本名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { wordBookRepository.createWordBook(name) }
                .onSuccess { id ->
                    selectedBooks = selectedBooks + id
                    newBookName = ""
                    message = null
                    books = wordBookRepository.getWordBooks()
                }
                .onFailure { message = "创建失败：${it.message}" }
        }
    }

    fun save() {
        val current = detail ?: return
        val selections = selectedDefinitions.map { id ->
            DefinitionSelection(id, selectedExamples[id].orEmpty().toList())
        }
        val errorMessage = when {
            selectedBooks.isEmpty() -> "请选择生词本"
            selections.isEmpty() -> "请选择至少一条释义"
            else -> null
        }
        if (errorMessage != null) {
            message = errorMessage
            return
        }
        viewModelScope.launch {
            runCatching {
                wordBookRepository.saveWordToBooks(
                    SaveWordRequest(
                        wordId = current.word.wordId,
                        wordBookIds = selectedBooks.toList(),
                        selections = selections,
                    ),
                )
            }.onSuccess {
                message = "已保存"
                saved = true
            }.onFailure { message = "保存失败：${it.message}" }
        }
    }
}

/** 生词本管理（FR-4）：列表（响应式）+ 建改删。 */
class WordBooksViewModel(
    private val wordBookRepository: WordBookRepository,
) : ViewModel() {

    var books by mutableStateOf<List<WordBookSummary>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            wordBookRepository.observeWordBooks().collect { books = it }
        }
    }

    fun createBook(name: String) = launchAction { wordBookRepository.createWordBook(name) }

    fun renameBook(wordBookId: Long, newName: String) = launchAction {
        wordBookRepository.renameWordBook(wordBookId, newName)
    }

    fun deleteBook(wordBookId: Long) = launchAction { wordBookRepository.deleteWordBook(wordBookId) }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { message = it.message }
                .onSuccess { message = null }
        }
    }
}

/** 本内词条（FR-4 移除词）。 */
class BookDetailViewModel(
    private val wordBookRepository: WordBookRepository,
) : ViewModel() {

    var words by mutableStateOf<List<WordBookWord>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun load(wordBookId: Long) {
        viewModelScope.launch { words = wordBookRepository.getWordBookWords(wordBookId) }
    }

    fun removeWord(wordBookId: Long, wordId: Long) {
        viewModelScope.launch {
            runCatching { wordBookRepository.removeWordFromWordBook(wordBookId, wordId) }
                .onSuccess { words = wordBookRepository.getWordBookWords(wordBookId) }
                .onFailure { message = it.message }
        }
    }
}
