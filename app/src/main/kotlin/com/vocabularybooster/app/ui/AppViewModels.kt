package com.vocabularybooster.app.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocabularybooster.app.di.ImportEngineFactory
import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.DefinitionSelection
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.importing.DetectedEncoding
import com.vocabularybooster.importing.ImportEngine
import com.vocabularybooster.importing.ImportReport
import com.vocabularybooster.importing.ImportTarget
import com.vocabularybooster.importing.ParsedLine
import com.vocabularybooster.importing.PreviewLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 删除（FR-4 拦截规则 → 中文文案，Phase 6）：勋章本不可删 / 有派生子本先删派生本。 */
    fun deleteBook(wordBookId: Long) {
        viewModelScope.launch {
            runCatching { wordBookRepository.deleteWordBook(wordBookId) }
                .onSuccess { message = null }
                .onFailure { e ->
                    message = when (e) {
                        is WordBookDeletionException -> when (e.reason) {
                            WordBookDeletionException.Reason.BOOK_HAS_COMPLETION_MEDAL ->
                                "该生词本已获得完成勋章，不能删除"
                            WordBookDeletionException.Reason.HAS_DERIVED_CHILDREN ->
                                "该生词本存在派生生词本，请先删除派生本"
                        }
                        else -> e.message
                    }
                }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { message = it.message }
                .onSuccess { message = null }
        }
    }
}

/** 勋章墙（FR-13，Phase 6）：只读列表（earnedAt 倒序），全部类型统一渲染。 */
class AchievementsViewModel(
    private val achievementRepository: AchievementRepository,
) : ViewModel() {

    var achievements by mutableStateOf<List<Achievement>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            runCatching { achievementRepository.getAchievements() }
                .onSuccess { achievements = it }
                .onFailure { message = "勋章加载失败：${it.message}" }
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

/** TXT 导入（FR-14，Phase 7）：选文件 → 检测+预览 → 选本 → 导入中 → 报告（IMPORT_SPEC §5）。 */
class ImportViewModel(
    private val engineFactory: ImportEngineFactory,
    private val wordBookRepository: WordBookRepository,
) : ViewModel() {

    var state by mutableStateOf<ImportUiState>(ImportUiState.PickFile)
        private set
    var books by mutableStateOf<List<WordBookSummary>>(emptyList())
        private set
    var selectedBookId by mutableStateOf<Long?>(null)
    var createNewBook by mutableStateOf(false)
    var newBookName by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    private var engine: ImportEngine? = null
    private var encoding: DetectedEncoding? = null
    private var importJob: Job? = null

    fun onFilePicked(uri: Uri) {
        val newEngine = engineFactory.create(uri)
        engine = newEngine
        viewModelScope.launch {
            runCatching {
                val detected = withContext(Dispatchers.IO) { newEngine.detectEncoding() }
                if (detected is DetectedEncoding.Unsupported) {
                    return@runCatching ImportUiState.Failed("无法识别文件编码：${detected.reason}")
                }
                encoding = detected
                val lines = withContext(Dispatchers.IO) { newEngine.preview(detected) }
                books = wordBookRepository.getWordBooks()
                selectedBookId = books.firstOrNull()?.wordBook?.wordBookId
                createNewBook = books.isEmpty()
                newBookName = ""
                ImportUiState.Preview(encodingLabel(detected), lines)
            }.onSuccess { state = it }
                .onFailure { state = ImportUiState.Failed("读取文件失败：${it.message}") }
        }
    }

    fun startImport() {
        val currentEngine = engine
        val detected = encoding
        if (currentEngine == null || detected == null) return
        val target = resolveTarget() ?: return // 失败提示已在 resolveTarget 内设置
        message = null
        importJob = viewModelScope.launch {
            state = ImportUiState.Importing(linesRead = 0, imported = 0, invalid = 0)
            // 取消须与失败分流（取消 = 整体回滚，文案不同）；异常种类不可穷举（IO/解码/DB）——仅记文案
            @Suppress("TooGenericExceptionCaught")
            try {
                val report = currentEngine.import(detected, target) { progress ->
                    state = ImportUiState.Importing(progress.linesRead, progress.imported, progress.invalid)
                }
                state = ImportUiState.Report(report)
            } catch (e: CancellationException) {
                state = ImportUiState.Failed("导入已取消，目标生词本未变化")
                throw e
            } catch (e: Exception) {
                state = ImportUiState.Failed("导入失败：${e.message}")
            }
        }
    }

    /** 目标校验漏斗：返回 null 前置好对应用户提示（名称空 / 未选本）。 */
    private fun resolveTarget(): ImportTarget? {
        val target = if (createNewBook) {
            newBookName.trim().takeIf { it.isNotEmpty() }?.let { ImportTarget.NewBook(it) }
        } else {
            selectedBookId?.let { ImportTarget.ExistingBook(it) }
        }
        if (target == null) {
            message = if (createNewBook) "新建生词本名称不能为空" else "请选择目标生词本"
        }
        return target
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun reset() {
        importJob?.cancel()
        importJob = null
        engine = null
        encoding = null
        selectedBookId = null
        createNewBook = false
        newBookName = ""
        message = null
        state = ImportUiState.PickFile
    }

    private fun encodingLabel(encoding: DetectedEncoding): String = when (encoding) {
        is DetectedEncoding.Utf8 -> if (encoding.hasBom) "UTF-8（带 BOM）" else "UTF-8"
        DetectedEncoding.Utf16LE -> "UTF-16LE"
        DetectedEncoding.Utf16BE -> "UTF-16BE"
        DetectedEncoding.Gb18030 -> "GB18030"
        is DetectedEncoding.Unsupported -> "不受支持"
    }
}

/** 导入流程五态（IMPORT_SPEC §5）：UI 只渲染状态 + 转发意图。 */
sealed interface ImportUiState {
    data object PickFile : ImportUiState

    data class Preview(val encodingLabel: String, val lines: List<PreviewLine>) : ImportUiState

    data class Importing(val linesRead: Long, val imported: Long, val invalid: Long) : ImportUiState

    data class Report(val report: ImportReport) : ImportUiState

    data class Failed(val message: String) : ImportUiState
}
