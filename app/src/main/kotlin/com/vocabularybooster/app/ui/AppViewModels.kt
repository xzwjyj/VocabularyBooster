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
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.model.LearningStatsSummary
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SaveWordRequest
import com.vocabularybooster.domain.model.StatsGranularity
import com.vocabularybooster.domain.model.StatsPoint
import com.vocabularybooster.domain.model.Word
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookWord
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.LearningStatsRepository
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.importing.DetectedEncoding
import com.vocabularybooster.importing.ImportEngine
import com.vocabularybooster.importing.ImportReport
import com.vocabularybooster.importing.ImportTarget
import com.vocabularybooster.importing.ParsedLine
import com.vocabularybooster.importing.PreviewLine
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            // 加载新词前清空旧词的选中状态，避免跨词残留导致校验失败
            selectedDefinitions = emptySet()
            selectedExamples = emptyMap()
            selectedBooks = emptySet()
            message = null
            saved = false
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

    /** 重置保存状态，用于每次打开保存对话框时清除上一次粘滞的 saved 状态。 */
    fun resetSaved() {
        saved = false
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

/** 学习统计（FR-20，Phase 8.6）：勋章页汇总卡与统计详情曲线共享同一 activity 作用域实例。 */
class StatsViewModel(
    private val statsRepository: LearningStatsRepository,
) : ViewModel() {

    var summary by mutableStateOf<LearningStatsSummary?>(null)
        private set
    var granularity by mutableStateOf(StatsGranularity.DAY)
        private set
    var points by mutableStateOf<List<StatsPoint>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { statsRepository.summary() }
                .onSuccess {
                    summary = it
                    message = null
                }
                .onFailure { message = "统计加载失败：${it.message}" }
            loadSeries()
        }
    }

    fun onGranularityChange(value: StatsGranularity) {
        if (value == granularity) return
        granularity = value
        viewModelScope.launch { loadSeries() }
    }

    private suspend fun loadSeries() {
        runCatching { statsRepository.series(granularity) }
            .onSuccess { points = it }
            .onFailure { message = "统计加载失败：${it.message}" }
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

/**
 * 设置页（FR-15，Phase 8）：五项快照加载 + 变更即时持久化（无保存按钮）；
 * 范围校验在端口（铁律 2），失败只提示并回滚显示（回显当前持久值）。
 * 生效时机由既有读取点保证：开关/语速/音调下一 Segment、窗口时长下一窗口、
 * groupSize 仅新会话（FR-6 第 4 条，UI 固定提示）。
 */
class SettingsViewModel(
    private val settingsRepository: LearningSettingsRepository,
    private val synthesizer: SpeechSynthesizer,
) : ViewModel() {

    var loaded by mutableStateOf(false)
        private set

    /** 持久值（回滚显示的基准）；[groupSizeText] 是输入框的显示态。 */
    var groupSize by mutableStateOf(LearningSettingsRepository.DEFAULT_GROUP_SIZE)
        private set
    var groupSizeText by mutableStateOf(LearningSettingsRepository.DEFAULT_GROUP_SIZE.toString())
        private set
    var commandWindowMs by mutableStateOf(LearningSettingsRepository.DEFAULT_COMMAND_WINDOW_MS)
        private set
    var toggles by mutableStateOf(PlaybackToggles.DEFAULT)
        private set
    var ttsRate by mutableStateOf(LearningSettingsRepository.DEFAULT_TTS_RATE)
        private set
    var ttsPitch by mutableStateOf(LearningSettingsRepository.DEFAULT_TTS_PITCH)
        private set

    /** 发音口音（FR-22）：美音（缺省）/ 英音；只影响英文段，下一 Segment 生效。 */
    var ttsAccent by mutableStateOf(LearningSettingsRepository.DEFAULT_TTS_ACCENT)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // —— Phase 8.6 TTS 音色（FR-19）：null id = 跟随系统默认 ——

    /** 引擎可用音色（引擎未就绪时为空——init 内等待就绪后枚举）。 */
    var voicesEn by mutableStateOf<List<TtsVoice>>(emptyList())
        private set
    var voicesZh by mutableStateOf<List<TtsVoice>>(emptyList())
        private set
    var voiceEnId by mutableStateOf<String?>(null)
        private set
    var voiceZhId by mutableStateOf<String?>(null)
        private set

    /** 设备真英音音色（FR-22）：空 = 无英音，选英音时提示将回退美音。 */
    var voicesEnGb by mutableStateOf<List<TtsVoice>>(emptyList())
        private set

    /** 音色枚举完成（含引擎就绪等待）——英音缺失提示等枚举定论，避免先闪后隐。 */
    var voicesEnumerated by mutableStateOf(false)
        private set

    /** 英音缺失提示可见性：已选英音、枚举已完成且设备无英音音色（朗读将回退美音，不中断）。 */
    val enGbMissingHint: Boolean
        get() = ttsAccent == Lang.EN_GB && voicesEnumerated && voicesEnGb.isEmpty()

    init {
        viewModelScope.launch {
            runCatching {
                groupSize = settingsRepository.getGroupSize()
                groupSizeText = groupSize.toString()
                commandWindowMs = settingsRepository.getCommandWindowMs()
                toggles = settingsRepository.getPlaybackToggles()
                ttsRate = settingsRepository.getTtsRate()
                ttsPitch = settingsRepository.getTtsPitch()
                ttsAccent = settingsRepository.getTtsAccent()
                voiceEnId = settingsRepository.getTtsVoiceEn()
                voiceZhId = settingsRepository.getTtsVoiceZh()
            }.onFailure { message = "设置加载失败：${it.message}" }
            loaded = true
        }
        // 音色枚举须等引擎就绪（INITIALIZING → READY/UNAVAILABLE，上限 5s；超时保持空列表）
        viewModelScope.launch {
            withTimeoutOrNull(VOICE_ENUM_WAIT_MS) {
                synthesizer.readiness.first { it != Readiness.INITIALIZING }
            }
            voicesEn = synthesizer.availableVoices(Lang.EN_US)
            voicesZh = synthesizer.availableVoices(Lang.ZH_CN)
            voicesEnGb = synthesizer.availableVoices(Lang.EN_GB)
            voicesEnumerated = true
        }
    }

    /** groupSize 文本输入：可解析为整数即转发端口持久化（范围由端口裁决）；空/非数字只更新显示。 */
    fun onGroupSizeChange(text: String) {
        groupSizeText = text
        val value = text.trim().toIntOrNull() ?: return
        viewModelScope.launch {
            runCatching { settingsRepository.setGroupSize(value) }
                .onSuccess {
                    groupSize = value
                    message = null
                }
                .onFailure { e ->
                    message = "保存失败：${e.message}"
                    groupSizeText = groupSize.toString()
                }
        }
    }

    /** 窗口时长滑条变更（命名避开 var 属性的 JVM setter 签名）。 */
    fun onWindowChange(value: Long) {
        viewModelScope.launch {
            runCatching { settingsRepository.setCommandWindowMs(value) }
                .onSuccess {
                    commandWindowMs = value
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 六项播放开关整组更新（UI 侧 copy 单字段；全关不拦截——会话开始时裁决，LE spec §3）。 */
    fun updateToggles(value: PlaybackToggles) {
        viewModelScope.launch {
            runCatching { settingsRepository.setPlaybackToggles(value) }
                .onSuccess {
                    toggles = value
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 语速滑条变更。 */
    fun onRateChange(value: Float) {
        viewModelScope.launch {
            runCatching { settingsRepository.setTtsRate(value) }
                .onSuccess {
                    ttsRate = value
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 音调滑条变更。 */
    fun onPitchChange(value: Float) {
        viewModelScope.launch {
            runCatching { settingsRepository.setTtsPitch(value) }
                .onSuccess {
                    ttsPitch = value
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 选英语音色（null = 跟随系统）；变更下一朗读段生效。 */
    fun onVoiceEnChange(id: String?) {
        viewModelScope.launch {
            runCatching { settingsRepository.setTtsVoiceEn(id) }
                .onSuccess {
                    voiceEnId = id
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 选中文音色（null = 跟随系统）；变更下一朗读段生效。 */
    fun onVoiceZhChange(id: String?) {
        viewModelScope.launch {
            runCatching { settingsRepository.setTtsVoiceZh(id) }
                .onSuccess {
                    voiceZhId = id
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    /** 切发音口音（FR-22，美音/英音二选一）；下一英文段生效，中文段不受影响。 */
    fun onAccentChange(value: Lang) {
        viewModelScope.launch {
            runCatching { settingsRepository.setTtsAccent(value) }
                .onSuccess {
                    ttsAccent = value
                    message = null
                }
                .onFailure { e -> message = "保存失败：${e.message}" }
        }
    }

    private companion object {
        /** 引擎就绪等待上限（对齐 [com.vocabularybooster.platform.TtsSpeechSynthesizer] 初始化节奏）。 */
        const val VOICE_ENUM_WAIT_MS: Long = 5_000L
    }
}

/**
 * 词条选择编辑（FR-17，Phase 8.5）：预填当前已保存选择 → 勾选增删 → 单事务整组替换。
 * 校验在端口（铁律 2）；全不勾释义由 VM 前置拦截（与端口 ≥1 守卫同文案）。
 * 编辑零接触掌握状态与词条行（队列位置不变，shared 层保证）。
 */
class WordSelectionEditorViewModel(
    private val wordRepository: WordRepository,
    private val wordBookRepository: WordBookRepository,
) : ViewModel() {

    var loaded by mutableStateOf(false)
        private set
    var detail by mutableStateOf<WordDetail?>(null)
        private set
    var selectedDefinitions by mutableStateOf<Set<Long>>(emptySet())
        private set
    var selectedExamples by mutableStateOf<Map<Long, Set<Long>>>(emptyMap())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set

    private var bookId: Long = -1L
    private var wordId: Long = -1L

    fun load(targetBookId: Long, targetWordId: Long, wordText: String) {
        // 每次打开清空旧状态（跨词残留防线，同 task#12 手法）
        selectedDefinitions = emptySet()
        selectedExamples = emptyMap()
        message = null
        saved = false
        loaded = false
        bookId = targetBookId
        wordId = targetWordId
        viewModelScope.launch {
            val wordDetail = wordRepository.lookup(wordText)
            if (wordDetail == null) {
                detail = null
                message = "词库中找不到「$wordText」"
                loaded = true
                return@launch
            }
            detail = wordDetail
            runCatching { wordBookRepository.getWordSelections(targetBookId, targetWordId) }
                .onSuccess { snapshot ->
                    // 预填当前选择；词不在本内（snapshot=null）按空选择起绘（保存前端口会拒绝）
                    selectedDefinitions = snapshot?.selections?.map { it.definitionEntryId }?.toSet().orEmpty()
                    selectedExamples = snapshot?.selections
                        ?.filter { it.exampleIds.isNotEmpty() }
                        ?.associate { it.definitionEntryId to it.exampleIds.toSet() }
                        .orEmpty()
                }
                .onFailure { message = "选择加载失败：${it.message}" }
            loaded = true
        }
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

    fun save() {
        if (bookId < 0) return
        if (selectedDefinitions.isEmpty()) {
            message = "至少保留一条释义（不需要该词请用「移除」）"
            return
        }
        val selections = selectedDefinitions.map { id ->
            DefinitionSelection(id, selectedExamples[id].orEmpty().toList())
        }
        viewModelScope.launch {
            runCatching { wordBookRepository.updateWordSelections(bookId, wordId, selections) }
                .onSuccess {
                    message = "已保存"
                    saved = true
                }
                .onFailure { message = "保存失败：${it.message}" }
        }
    }
}
