package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightImportRepository
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightPlaybackContentRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.importing.DetectedEncoding
import com.vocabularybooster.importing.ImportEngine
import com.vocabularybooster.importing.ImportTarget
import com.vocabularybooster.platform.FileBytesSource
import com.vocabularybooster.platform.TextLineSource
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import com.vocabularybooster.playback.FakeLearningSettingsRepository
import com.vocabularybooster.playback.SegmentBuilder
import com.vocabularybooster.playback.SegmentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ImportEngine 真实 JDBC 集成（IMPORT_SPEC §4–§6，Phase 7）：
 * - TC-IMP-03 守恒恒等式 + ImportFinished 仅在成功提交且 ≥1 写入后发布；
 * - TC-IMP-05 去重三层（文件内首见 / 全局复用且不在本 / 本内重复含补写）；
 * - TC-IMP-07 事务回滚：中途异常与协程取消 → 目标本零变化、零事件、不建空本；
 * - TC-IMP-08 pendingTranslation 生命周期（词行 null / 带译文落库 / 补写一次 / 已有不覆盖）；
 * - 导入词可开学习会话（ADR-002 Q2 全本入队 + selectedDefinitions 空 → PRON/SPELL 段可播）。
 * 纯函数口径（TC-IMP-01/02/06）见 commonTest；性能基准（TC-IMP-04）见 ImportPerfTest。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val db = TestDb.inMemory()
    private val clock = FixedClock()
    private val bus = DefaultDomainEventBus()
    private val repo = SqlDelightImportRepository(db.database, DispatchersForTest)
    private val recordedEvents = mutableListOf<DomainEvent>()

    init {
        scope.launch { bus.events.collect { recordedEvents += it } }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // —— 装配/种子 ——

    private fun newEngine(
        lines: List<String>,
        dictionaryProvider: DictionaryProvider? = null,
    ): ImportEngine = ImportEngine(
        bytesSource = FakeBytesSource("apple\t苹果\n".encodeToByteArray()),
        lineSource = FakeLineSource(lines),
        repository = repo,
        eventBus = bus,
        clock = clock,
        dictionaryProvider = dictionaryProvider,
    )

    private fun newLearningEngine(): DefaultLearningEngine {
        val sessionRepo = SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
        return DefaultLearningEngine(
            sessionRepository = sessionRepo,
            settingsRepository = FakeLearningSettingsRepository(),
            masteryMarker = MasteryMarker(sessionRepo, clock),
            wordBookDeriver = WordBookDeriver(
                wordBookRepository = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest),
                clock = clock,
            ),
        )
    }

    private fun seedWord(text: String, normalizedText: String): Long {
        val now = clock.now().toEpochMilliseconds()
        db.database.wordQueries.insertWord(text, normalizedText, null, null, null, now, now)
        return db.database.wordQueries.selectLastInsertRowId().executeAsOne()
    }

    private fun seedBook(name: String, entries: List<Pair<Long, String?>>): Long {
        val now = clock.now().toEpochMilliseconds()
        db.database.wordBookQueries.insertOriginalWordBook(name, null, now, now)
        val bookId = db.database.wordBookQueries.selectLastInsertRowId().executeAsOne()
        entries.forEachIndexed { index, (wordId, pending) ->
            db.database.wordBookEntryQueries.insertEntry(bookId, wordId, index.toLong(), pending, now)
        }
        return bookId
    }

    private fun wordIdOf(normalizedText: String): Long =
        db.database.wordQueries.selectByNormalizedText(normalizedText).executeAsOne().wordId

    private fun pendingOf(bookId: Long, wordId: Long): String? =
        db.database.wordBookEntryQueries.selectEntryByWord(bookId, wordId).executeAsOne().pendingTranslation

    private fun wordCount(): Long = db.database.wordQueries.countAll().executeAsOne()

    private fun bookCount(): Int = db.database.wordBookQueries.selectAllWordBooks().executeAsList().size

    private fun flushEvents() {
        dispatcher.scheduler.runCurrent()
    }

    // —— TC-IMP-03：守恒 + 事件 ——

    @Test
    fun conservationIdentityHoldsAndEventPublishedAfterCommit() = runTest(dispatcher.scheduler) {
        val engine = newEngine(listOf("", "apple\t苹果", "12345 x", "take off", "苹果 apple", "banana", "|||"))
        // detectEncoding 分块累积接线闭环（TC-IMP-01 的引擎侧路径）
        assertEquals(DetectedEncoding.Utf8(hasBom = false), engine.detectEncoding())

        val report = engine.import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("导入测试"))

        assertEquals(6L, report.totalLines) // 空行 ignored 不计有效行
        assertEquals(3L, report.imported) // apple / take / banana
        assertEquals(0L, report.reusedWords)
        assertEquals(0L, report.duplicatesInFile)
        assertEquals(0L, report.duplicatesInBook)
        assertEquals(3L, report.invalid) // 12345 x / 苹果 apple / |||
        assertEquals(3, report.invalidSamples.size)
        assertEquals(
            report.totalLines,
            report.imported + report.reusedWords + report.duplicatesInFile +
                report.duplicatesInBook + report.invalid,
        )
        // 原文保留大小写（normalizedText 才是查询/去重键）
        assertEquals("apple", db.database.wordQueries.selectByNormalizedText("apple").executeAsOne().text)

        flushEvents()
        val event = assertIs<DomainEvent.ImportFinished>(recordedEvents.single())
        assertEquals(report.targetWordBookId, event.targetWordBookId)
        assertEquals(3L, event.imported)
        assertEquals(3L, event.invalid)
    }

    // —— TC-IMP-05：去重三层 ——

    @Test
    fun dedupLayersSplitAcrossFileGlobalAndBookScopes() = runTest(dispatcher.scheduler) {
        val appleId = seedWord("Apple", "apple") // 全局已有、不在本
        val bananaId = seedWord("banana", "banana")
        val bookId = seedBook("目标本", listOf(bananaId to null)) // 本内已有（entryOrder=0，无译文）
        val engine = newEngine(
            listOf("apple\t苹果", "Apple,红苹果", "banana\t香蕉", "banana", "cherry", "cherry"),
        )

        val report = engine.import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.ExistingBook(bookId))

        // cherry=新词；apple=全局复用+新入本；Apple/banana(2)/cherry(2)=文件内；banana=本内重复(补写)
        assertEquals(6L, report.totalLines)
        assertEquals(1L, report.imported)
        assertEquals(1L, report.reusedWords)
        assertEquals(3L, report.duplicatesInFile)
        assertEquals(1L, report.duplicatesInBook)
        assertEquals(1L, report.updated)
        assertEquals(0L, report.invalid)
        assertEquals(
            report.totalLines,
            report.imported + report.reusedWords + report.duplicatesInFile +
                report.duplicatesInBook + report.invalid,
        )
        // 全局零重复插词：apple 复用原 wordId，Word 表只多 cherry
        assertEquals(appleId, wordIdOf("apple"))
        assertEquals(3L, wordCount())
        // 本内 entryOrder 延续（banana=0 → apple=1 → cherry=2）+ 补写译文生效
        assertEquals("苹果", pendingOf(bookId, appleId))
        assertEquals(1L, db.database.wordBookEntryQueries.selectEntryByWord(bookId, appleId)
            .executeAsOne().entryOrder)
        assertEquals(2L, db.database.wordBookEntryQueries.selectEntryByWord(bookId, wordIdOf("cherry"))
            .executeAsOne().entryOrder)
        assertEquals("香蕉", pendingOf(bookId, bananaId))
    }

    // —— TC-IMP-08：pendingTranslation 生命周期 ——

    @Test
    fun pendingTranslationBackfillsOnceAndNeverOverwrites() = runTest(dispatcher.scheduler) {
        val first = newEngine(listOf("apple\t苹果", "banana"))
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("词书A"))
        assertEquals(2L, first.imported)
        val bookId = first.targetWordBookId
        val appleId = wordIdOf("apple")
        val bananaId = wordIdOf("banana")
        assertEquals("苹果", pendingOf(bookId, appleId)) // 带译文行 → 落库
        assertNull(pendingOf(bookId, bananaId)) // 词行 → null

        // 第二轮：banana 本行带译文且库内为空 → 补写一次（duplicatesInBook 且 updated）
        val second = newEngine(listOf("banana\t香蕉"))
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.ExistingBook(bookId))
        assertEquals(1L, second.duplicatesInBook)
        assertEquals(1L, second.updated)
        assertEquals(0L, second.imported)
        assertEquals("香蕉", pendingOf(bookId, bananaId))

        // 第三轮：库内已有译文 → 不覆盖、不计 updated（纯重复 → 零事件）
        val third = newEngine(listOf("banana\t鸭梨", "apple\t新译"))
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.ExistingBook(bookId))
        assertEquals(2L, third.duplicatesInBook)
        assertEquals(0L, third.updated)
        assertEquals("香蕉", pendingOf(bookId, bananaId))
        assertEquals("苹果", pendingOf(bookId, appleId))

        // 事件口径：每轮至少一行写入才发布；第三轮纯重复 → 不发
        flushEvents()
        assertEquals(2, recordedEvents.size)
        recordedEvents.forEach { assertIs<DomainEvent.ImportFinished>(it) }
    }

    // —— TC-IMP-07：事务回滚 ——

    @Test
    fun midStreamExceptionRollsBackEverythingAndPublishesNothing() = runTest(dispatcher.scheduler) {
        val wordsBefore = wordCount()
        val booksBefore = bookCount()
        val engine = ImportEngine(
            bytesSource = FakeBytesSource(ByteArray(0)),
            lineSource = ExplodingLineSource(listOf("apple\t苹果", "cherry\t樱桃", "banana"), failAt = 2),
            repository = repo,
            eventBus = bus,
            clock = clock,
        )

        assertFailsWith<IllegalStateException> {
            engine.import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("失败本"))
        }

        assertEquals(wordsBefore, wordCount()) // 已写两行也整体回滚
        assertEquals(booksBefore, bookCount()) // 空本防线：失败不建本
        assertNull(db.database.wordQueries.selectByNormalizedText("cherry").executeAsOneOrNull())
        flushEvents()
        assertTrue(recordedEvents.isEmpty())
    }

    @Test
    fun cancellationMidImportRollsBackInPlace() = runTest(dispatcher.scheduler) {
        val appleId = seedWord("apple", "apple")
        val bookId = seedBook("目标本", listOf(appleId to null))
        val wordsBefore = wordCount()
        val source = GatedLineSource(listOf("cherry\t樱桃", "durian\t榴莲", "fig\t无花果"), gateAfter = 3)
        val engine = ImportEngine(
            bytesSource = FakeBytesSource(ByteArray(0)),
            lineSource = source,
            repository = repo,
            eventBus = bus,
            clock = clock,
        )
        var error: Throwable? = null
        // 独立线程派发：导入在 gate 挂起，测试线程才能下达取消（TC-IMP-04 取消语义同源）
        val job = launch(Dispatchers.Default) {
            try {
                engine.import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.ExistingBook(bookId))
            } catch (t: Throwable) {
                error = t
            }
        }
        source.reachedGate.await()
        job.cancelAndJoin()

        assertIs<CancellationException>(error)
        assertEquals(wordsBefore, wordCount())
        assertNull(db.database.wordQueries.selectByNormalizedText("cherry").executeAsOneOrNull())
        assertEquals(1, db.database.wordBookEntryQueries.selectEntryWordsForBook(bookId)
            .executeAsList().size) // 只剩种子 apple
        flushEvents()
        assertTrue(recordedEvents.isEmpty())
    }

    // —— 导入词可开学习会话（AUDIT #5 / ADR-002）——

    @Test
    fun importedWordsStartPlayableSessionWithPronSpellSegments() = runTest(dispatcher.scheduler) {
        val bookId = newEngine(listOf("apple\t苹果", "banana"))
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("会话本"))
            .targetWordBookId
        val learning = newLearningEngine()
        val sessionId = assertIs<StartResult.Started>(learning.startSession(bookId))
            .snapshot.session.sessionId
        val appleId = wordIdOf("apple")
        assertEquals(appleId, assertIs<AdvanceResult.NextWord>(learning.advance(sessionId)).ref.wordId)

        // 导入词无词典释义：selectedDefinitions 空 → 词级段 PRON/SPELL 恰两段可播
        val content = assertNotNull(
            SqlDelightPlaybackContentRepository(db.database, DispatchersForTest)
                .getPlaybackContent(bookId, appleId),
        )
        assertTrue(content.selectedDefinitions.isEmpty())
        assertTrue(content.examplesByDefinitionEntryId.isEmpty())
        val segments = SegmentBuilder.buildSegments(content, PlaybackToggles())
        assertEquals(listOf(SegmentType.PRONUNCIATION, SegmentType.SPELLING), segments.map { it.type })
    }

    // —— SCR-TXTDICTENRICH 词典富化（TC-IMP-09…14；SenseMatcher 纯函数口径见 commonTest）——

    /** hamper 词典词条：verb「妨碍」+ noun「篮子」各 1 例句，双侧音标齐。 */
    private fun hamperDict() = DictionaryWord(
        text = "hamper",
        ipaAm = "ˈhæmpər",
        ipaBr = "ˈhæmpə(r)",
        definitions = listOf(
            DictionaryDefinitionEntry(
                partOfSpeech = "verb",
                partOfSpeechOrder = 0,
                definitionOrder = 0,
                meaningEN = "hamper, hinder",
                meaningCN = "妨碍，阻碍",
                examples = listOf(
                    DictionaryExample(
                        sentence = "Rain hampered the rescue.",
                        chineseTranslation = "大雨妨碍了救援。",
                        exampleOrder = 0,
                    ),
                ),
            ),
            DictionaryDefinitionEntry(
                partOfSpeech = "noun",
                partOfSpeechOrder = 1,
                definitionOrder = 0,
                meaningEN = "a large basket",
                meaningCN = "（带盖的）大篮子",
                examples = listOf(
                    DictionaryExample(
                        sentence = "A picnic hamper sat on the grass.",
                        chineseTranslation = "草地上放着一只野餐篮。",
                        exampleOrder = 0,
                    ),
                ),
            ),
        ),
    )

    private fun hamperProvider() = FakeDictionaryProvider(mapOf("hamper" to hamperDict()))

    @Test // TC-IMP-09：裸词行 → 母数据全量 + 全释义/全例句勾选 + IPA 补齐 + 守恒式不变
    fun bareWordEnrichesAllSensesAndSelectsEverything() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("富化本"))

        assertEquals(1L, report.imported)
        assertEquals(1L, report.enriched)
        assertEquals(0L, report.enrichedMatched)
        assertEquals(
            report.totalLines,
            report.imported + report.reusedWords + report.duplicatesInFile +
                report.duplicatesInBook + report.invalid,
        )
        val wordId = wordIdOf("hamper")
        val wordRow = db.database.wordQueries.selectByNormalizedText("hamper").executeAsOne()
        assertEquals("ˈhæmpər", wordRow.ipaAm)
        assertEquals("ˈhæmpə(r)", wordRow.ipaBr)
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(listOf("verb", "noun"), defs.map { it.partOfSpeech }) // 母数据全量
        val entryId = entryIdOf(report.targetWordBookId, wordId)
        assertNull(pendingOf(report.targetWordBookId, wordId)) // 正式释义在位 → 临时译文位空
        assertEquals(defs.map { it.definitionEntryId }, defSelections(entryId)) // 全释义勾选
        assertEquals(exampleIdsOf(defs), exampleSelections(entryId)) // 全例句勾选
    }

    @Test // TC-IMP-10：带译文行 → 母数据仍全量，勾选 = 匹配子集（verb「妨碍」）
    fun translatedLineSelectsMatchedSenseSubsetOnly() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\t妨碍"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("富化本"))

        assertEquals(1L, report.imported)
        assertEquals(1L, report.enriched)
        assertEquals(1L, report.enrichedMatched)
        val wordId = wordIdOf("hamper")
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(2, defs.size) // 母数据不受勾选子集影响
        val verbDef = defs.first { it.partOfSpeech == "verb" }
        val entryId = entryIdOf(report.targetWordBookId, wordId)
        assertEquals(listOf(verbDef.definitionEntryId), defSelections(entryId)) // 只勾动词
        assertEquals(exampleIdsOf(listOf(verbDef)), exampleSelections(entryId)) // 只勾其例句
        assertNull(pendingOf(report.targetWordBookId, wordId)) // 正式释义在位，临时译文不落库
    }

    @Test // TC-IMP-11：带译文零匹配 → D1 兜底全量（enrichedMatched 不计）
    fun zeroMatchTranslationFallsBackToAllSenses() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\t完全不相关的语义"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("富化本"))

        assertEquals(1L, report.enriched)
        assertEquals(0L, report.enrichedMatched)
        val entryId = entryIdOf(report.targetWordBookId, wordIdOf("hamper"))
        assertEquals(2, defSelections(entryId).size)
        assertNull(pendingOf(report.targetWordBookId, wordIdOf("hamper")))
    }

    @Test // TC-IMP-12：词典 miss（未收录 / 命中但零释义）→ 精确退化 Phase 7 原行为
    fun dictionaryMissKeepsLegacyPendingTranslationBehavior() = runTest(dispatcher.scheduler) {
        val provider = FakeDictionaryProvider(
            mapOf(
                "zzzvoid" to DictionaryWord(text = "zzzvoid", definitions = emptyList()), // 命中但零释义
            ),
        )
        val report = newEngine(listOf("zzzvoid\t某释义", "qqqmiss\t另一释义"), dictionaryProvider = provider)
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("退化本"))

        assertEquals(2L, report.imported)
        assertEquals(0L, report.enriched)
        val zzz = wordIdOf("zzzvoid")
        val qqq = wordIdOf("qqqmiss")
        assertEquals(0, db.database.definitionEntryQueries.selectDefinitionsForWord(zzz).executeAsList().size)
        assertEquals(0, db.database.definitionEntryQueries.selectDefinitionsForWord(qqq).executeAsList().size)
        assertEquals("某释义", pendingOf(report.targetWordBookId, zzz))
        assertEquals("另一释义", pendingOf(report.targetWordBookId, qqq))
    }

    @Test // TC-IMP-13：词已存在且有释义 → 绝不重建，只在既有行上勾选（FR-5 复用原则）
    fun existingWordWithDefinitionsOnlySelectsNeverRebuilds() = runTest(dispatcher.scheduler) {
        val wordId = seedWord("hamper", "hamper")
        val existingDefId = seedDefinition(wordId, meaningEN = "to hinder progress", meaningCN = "妨碍，阻碍")

        val report = newEngine(listOf("hamper\t妨碍"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("复用本"))

        assertEquals(1L, report.reusedWords)
        assertEquals(1L, report.enriched)
        assertEquals(1L, report.enrichedMatched)
        // 词典第 2 条 noun 不补入（既有释义即全部候选——宁少错建，SCR §3.3）
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(listOf(existingDefId), defs.map { it.definitionEntryId })
        assertEquals(listOf(existingDefId), defSelections(entryIdOf(report.targetWordBookId, wordId)))
        assertEquals(exampleIdsOf(defs), exampleSelections(entryIdOf(report.targetWordBookId, wordId)))
    }

    @Test // TC-IMP-14：词已存在且无释义（TXT 旧行）→ 母数据补齐 + 音标只补空缺（已有值绝不覆盖）
    fun existingWordWithoutDefinitionsBackfillsOnlyBlankPronunciation() = runTest(dispatcher.scheduler) {
        val now = clock.now().toEpochMilliseconds()
        db.database.wordQueries.insertWord("hamper", "hamper", "旧美音", null, null, now, now)
        val wordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()

        val report = newEngine(listOf("hamper"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("补齐本"))

        assertEquals(1L, report.reusedWords)
        assertEquals(1L, report.enriched)
        val wordRow = db.database.wordQueries.selectByNormalizedText("hamper").executeAsOne()
        assertEquals("旧美音", wordRow.ipaAm) // 已有值绝不覆盖
        assertEquals("ˈhæmpə(r)", wordRow.ipaBr) // 空缺侧补上
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(2, defs.size)
        assertEquals(defs.map { it.definitionEntryId }, defSelections(entryIdOf(report.targetWordBookId, wordId)))
    }

    @Test // 富化词条 re-import（duplicatesInBook）：勾选不动 + 临时译文不得回流（正式释义优先）
    fun enrichedEntryReimportDoesNotTouchSelectionsNorBackfillTranslation() = runTest(dispatcher.scheduler) {
        val bookId = newEngine(listOf("hamper"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("幂等本"))
            .targetWordBookId
        val wordId = wordIdOf("hamper")
        val entryId = entryIdOf(bookId, wordId)
        val defsBefore = defSelections(entryId)
        val examplesBefore = exampleSelections(entryId)

        val second = newEngine(listOf("hamper\t篮子"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.ExistingBook(bookId))

        assertEquals(1L, second.duplicatesInBook)
        assertEquals(0L, second.updated) // 释义勾选在位 → 不补写临时译文
        assertEquals(0L, second.enriched) // 富化只发生在新建 entry
        assertEquals(defsBefore, defSelections(entryId)) // 勾选零变化
        assertEquals(examplesBefore, exampleSelections(entryId))
        assertNull(pendingOf(bookId, wordId))
    }

    // —— SCR-TXTDICTENRICH v2 词性标记（TC-IMP-15；LineParser 剥离口径见 commonTest）——

    @Test // 词性独占行：母数据仍全量，勾选只入该词性（无译文 → enrichedMatched 不计）
    fun posMarkerOnlyLineSelectsThatPartOfSpeech() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\tn."), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("词性本"))

        assertEquals(1L, report.imported)
        assertEquals(1L, report.enriched)
        assertEquals(0L, report.enrichedMatched) // 词性过滤不占「按译文匹配」口径
        val wordId = wordIdOf("hamper")
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(listOf("verb", "noun"), defs.map { it.partOfSpeech }) // 母数据不受词性影响
        val nounDef = defs.single { it.partOfSpeech == "noun" }
        val entryId = entryIdOf(report.targetWordBookId, wordId)
        assertEquals(listOf(nounDef.definitionEntryId), defSelections(entryId)) // 只勾名词
        assertEquals(exampleIdsOf(listOf(nounDef)), exampleSelections(entryId)) // 只勾其例句
        assertNull(pendingOf(report.targetWordBookId, wordId))
    }

    @Test // 词性 + 译文两级过滤：词性先收窄池，译文在池内匹配（vt. 归 verb）
    fun posMarkerWithTranslationMatchesWithinPosPool() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\tvt. 妨碍"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("词性本"))

        assertEquals(1L, report.enriched)
        assertEquals(1L, report.enrichedMatched)
        val wordId = wordIdOf("hamper")
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(2, defs.size) // 母数据全量
        val verbDef = defs.single { it.partOfSpeech == "verb" }
        val entryId = entryIdOf(report.targetWordBookId, wordId)
        assertEquals(listOf(verbDef.definitionEntryId), defSelections(entryId))
        assertEquals(exampleIdsOf(listOf(verbDef)), exampleSelections(entryId))
    }

    @Test // 译文在词性池内零命中 → 兜底到**词性池**全量（不是全释义——「篮子」只与 noun 互含，但词性已锁定 verb）
    fun translationMissWithinPosPoolFallsBackToPosPool() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\tv. 篮子"), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("词性本"))

        assertEquals(1L, report.enriched)
        assertEquals(0L, report.enrichedMatched)
        val wordId = wordIdOf("hamper")
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        val verbDef = defs.single { it.partOfSpeech == "verb" }
        val entryId = entryIdOf(report.targetWordBookId, wordId)
        assertEquals(listOf(verbDef.definitionEntryId), defSelections(entryId)) // 池内兜底 = 只剩动词
        assertEquals(exampleIdsOf(listOf(verbDef)), exampleSelections(entryId))
    }

    @Test // 词性零命中（词典无该词性行）→ 兜底全释义（同 D1 理由：词表作者意图无法精确满足时保全数据）
    fun zeroHitPartOfSpeechFallsBackToAllSenses() = runTest(dispatcher.scheduler) {
        val report = newEngine(listOf("hamper\tint."), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("词性本"))

        assertEquals(1L, report.enriched)
        assertEquals(0L, report.enrichedMatched)
        val entryId = entryIdOf(report.targetWordBookId, wordIdOf("hamper"))
        assertEquals(2, defSelections(entryId).size)
        assertNull(pendingOf(report.targetWordBookId, wordIdOf("hamper")))
    }

    @Test // 词已有释义 + 词性标记 → 既有行上按词性勾选（不重建母数据、词典释义不补入）
    fun posMarkerFiltersExistingDefinitionRows() = runTest(dispatcher.scheduler) {
        val wordId = seedWord("hamper", "hamper")
        seedDefinition(wordId, "to hinder progress", "妨碍，阻碍", partOfSpeech = "verb")
        val nounDefId = seedDefinition(wordId, "a basket", "篮子", partOfSpeech = "noun", partOfSpeechOrder = 1L)

        val report = newEngine(listOf("hamper\tn."), dictionaryProvider = hamperProvider())
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("既有词性本"))

        assertEquals(1L, report.reusedWords)
        assertEquals(1L, report.enriched)
        // 既有 2 行即全部候选（复用原则：词典 verb/noun 行不补入不改写）
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(2, defs.size)
        assertEquals(listOf(nounDefId), defSelections(entryIdOf(report.targetWordBookId, wordId)))
        assertEquals(exampleIdsOf(listOf(defs.single { it.definitionEntryId == nounDefId })),
            exampleSelections(entryIdOf(report.targetWordBookId, wordId)))
    }

    @Test // TC-IMP-16：多词短语行整词入库 + 词典按短语整体富化（v1.5 短语修正，用户实录 roll out）
    fun multiWordPhraseLineImportsAndEnrichesAsSingleWord() = runTest(dispatcher.scheduler) {
        val provider = FakeDictionaryProvider(
            mapOf(
                "roll out" to DictionaryWord(
                    text = "roll out",
                    definitions = listOf(
                        DictionaryDefinitionEntry(
                            partOfSpeech = "verb",
                            partOfSpeechOrder = 0,
                            definitionOrder = 0,
                            meaningEN = "launch, introduce",
                            meaningCN = "推出，开展",
                            examples = listOf(
                                DictionaryExample(
                                    sentence = "They rolled out the new plan.",
                                    chineseTranslation = "他们推出了新计划。",
                                    exampleOrder = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val report = newEngine(listOf("roll out"), dictionaryProvider = provider)
            .import(DetectedEncoding.Utf8(hasBom = false), ImportTarget.NewBook("短语本"))

        assertEquals(1L, report.imported) // 整词一条——不是 roll + 译文 out
        assertEquals(1L, report.enriched)
        val wordId = wordIdOf("roll out") // normalizedText = 短语整体小写
        val defs = db.database.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(1, defs.size)
        assertEquals(defs.map { it.definitionEntryId }, defSelections(entryIdOf(report.targetWordBookId, wordId)))
        assertEquals(exampleIdsOf(defs), exampleSelections(entryIdOf(report.targetWordBookId, wordId)))
    }

    /** 预置一条既有释义 + 1 例句（模拟查词/种子导入词），返回 definitionEntryId。 */
    private fun seedDefinition(
        wordId: Long,
        meaningEN: String,
        meaningCN: String,
        partOfSpeech: String = "verb",
        partOfSpeechOrder: Long = 0L,
    ): Long {
        db.database.definitionEntryQueries.insertDefinitionEntry(
            wordId = wordId,
            partOfSpeech = partOfSpeech,
            partOfSpeechOrder = partOfSpeechOrder,
            definitionOrder = 0,
            meaningEN = meaningEN,
            meaningCN = meaningCN,
        )
        val defId = db.database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
        db.database.exampleQueries.insertExample(
            definitionEntryId = defId,
            sentence = "Bad weather hinders travel.",
            chineseTranslation = "恶劣天气妨碍出行。",
            sourceType = "TTS",
            sourceRef = null,
            licenseNote = null,
            audioUri = null,
            audioDurationMs = null,
            exampleOrder = 0,
        )
        return defId
    }

    private fun entryIdOf(bookId: Long, wordId: Long): Long =
        db.database.wordBookEntryQueries.selectEntryByWord(bookId, wordId).executeAsOne().wordBookEntryId

    private fun defSelections(entryId: Long): List<Long> =
        db.database.wordBookEntryDefinitionQueries.selectEntryDefinitions(entryId).executeAsList()
            .map { it.definitionEntryId }

    private fun exampleSelections(entryId: Long): List<Long> =
        db.database.wordBookEntryExampleSelectionQueries.selectExampleSelections(entryId).executeAsList()
            .map { it.exampleId }

    private fun exampleIdsOf(defs: List<com.vocabularybooster.db.DefinitionEntry>): List<Long> =
        defs.flatMap { def ->
            db.database.exampleQueries.selectExamplesForEntry(def.definitionEntryId).executeAsList()
                .map { it.exampleId }
        }

    /** 内存词典源：小写键查找（provider 契约——大小写不敏感由实现负责）。 */
    private class FakeDictionaryProvider(private val words: Map<String, DictionaryWord>) : DictionaryProvider {
        override suspend fun lookup(text: String): DictionaryWord? = words[text.trim().lowercase()]
    }

    // —— 内存 Fake 源（真实仓储 + 虚假平台端口）——

    /** 分块字节源：3 字节/块，驱动 detectEncoding 的分块累积路径。 */
    private class FakeBytesSource(private val bytes: ByteArray) : FileBytesSource {
        private var offset = 0
        override suspend fun readChunk(maxBytes: Int): ByteArray? {
            if (offset >= bytes.size) return null
            val len = minOf(maxBytes, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + len)
            offset += len
            return chunk
        }
    }

    /** 内存行源：编码对引擎只是决策标签（解码在平台 actual）。 */
    private class FakeLineSource(private val lines: List<String>) : TextLineSource {
        override fun lines(encoding: DetectedEncoding): Flow<String> = lines.asFlow()
    }

    /** 第 [failAt] 行抛异常（0 基）：前序行已写入事务，用于异常回滚。 */
    private class ExplodingLineSource(private val lines: List<String>, private val failAt: Int) : TextLineSource {
        override fun lines(encoding: DetectedEncoding): Flow<String> = flow {
            lines.forEachIndexed { index, line ->
                check(index != failAt) { "boom at $index" }
                emit(line)
            }
        }
    }

    /** 发出 [gateAfter] 行后挂起等取消：跨线程可控的取消注入点。 */
    private class GatedLineSource(private val lines: List<String>, private val gateAfter: Int) : TextLineSource {
        val reachedGate = CompletableDeferred<Unit>()

        override fun lines(encoding: DetectedEncoding): Flow<String> = flow {
            lines.forEachIndexed { index, line ->
                emit(line)
                if (index + 1 == gateAfter) {
                    reachedGate.complete(Unit)
                    hangHereAwaitingCancellation()
                }
            }
        }

        private suspend fun hangHereAwaitingCancellation() {
            CompletableDeferred<Unit>().await() // 永不完成：仅靠协程取消解除
        }
    }
}
