package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightImportRepository
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.importing.DetectedEncoding
import com.vocabularybooster.importing.ImportEngine
import com.vocabularybooster.importing.ImportTarget
import com.vocabularybooster.platform.FileBytesSource
import com.vocabularybooster.platform.TextLineSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TC-IMP-04 性能基准（IMPORT_SPEC §7，NFR-2，JVM 口径）：
 * - 10 万行 GB18030 内存 Fake 源全量导入 ≤ 60s（provider 未装配的退化路径；流式逐行，
 *   内存与文件大小无关）；
 * - SCR-TXTDICTENRICH：词典富化路径（每行 lookup 命中 + 释义/例句/勾选落库）10 万行
 *   预算放宽为 ≤ 120s（NFR-2 修订，IMPORT_SPEC v1.2 §7）——万行富化冒烟按 1/10 比例锁 12s；
 * - 导入中途取消 ≤ 1s 生效：整体回滚、目标本零变化（含已写入的 5 万行）。
 * GB18030 实际解码在平台 actual；JVM 基准以 GB18030 决策标签 + 等规模行载荷
 * 度量引擎吞吐（引擎只消费解码后的行，编码对引擎透明）。
 * 行为矩阵（守恒/回滚/事件）见 ImportEngineTest；本文件只锁预算。
 * （本文件按 AOR-007 先例不入门禁——机器级故障窗口。）
 */
class ImportPerfTest {

    /** 纯字母唯一词（i 的 26 进制展开）：保证基准行全部走 imported 快路径（数字会变 invalid）。 */
    private fun alphaWord(i: Int): String = buildString {
        var n = i
        do {
            append('a' + (n % 26))
            n /= 26
        } while (n > 0)
    }

    private val emptyBytesSource = object : FileBytesSource {
        override suspend fun readChunk(maxBytes: Int): ByteArray? = null
    }

    @Test
    fun hundredThousandLinesImportWithinSixtySeconds() {
        val db = TestDb.inMemory()
        try {
            // 全程钉在测试线程（与套件其余 JDBC 用例同口径）：ThreadedConnectionManager 的
            // 连接/事务都是 ThreadLocal，跨线程访问 in-memory 库行为未定义
            val engine = ImportEngine(
                bytesSource = emptyBytesSource,
                lineSource = GeneratedLineSource(count = 100_000) { "${alphaWord(it)}\t苹果" },
                repository = SqlDelightImportRepository(db.database, DispatchersForTest),
                eventBus = DefaultDomainEventBus(),
                clock = FixedClock(),
            )

            val startNanos = System.nanoTime()
            val report = runBlocking {
                engine.import(DetectedEncoding.Gb18030, ImportTarget.NewBook("性能基准"))
            }
            val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

            assertEquals(100_000L, report.imported)
            assertEquals(0L, report.invalid)
            assertTrue(elapsedSeconds <= 60.0, "10 万行导入 ${"%.1f".format(elapsedSeconds)}s 超出 60s 预算（NFR-2）")
        } finally {
            db.close()
        }
    }

    @Test // SCR-TXTDICTENRICH：富化路径万行冒烟（每行 lookup 命中 + 母数据 + 勾选落库；1/10 比例预算）
    fun tenThousandEnrichedLinesWithinTwelveSeconds() {
        val db = TestDb.inMemory()
        try {
            val engine = ImportEngine(
                bytesSource = emptyBytesSource,
                lineSource = GeneratedLineSource(count = 10_000) { "${alphaWord(it)}\t苹果" },
                repository = SqlDelightImportRepository(db.database, DispatchersForTest),
                eventBus = DefaultDomainEventBus(),
                clock = FixedClock(),
                dictionaryProvider = AllHitDictionaryProvider,
            )

            val startNanos = System.nanoTime()
            val report = runBlocking {
                engine.import(DetectedEncoding.Gb18030, ImportTarget.NewBook("富化基准"))
            }
            val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

            assertEquals(10_000L, report.imported)
            assertEquals(10_000L, report.enriched)
            assertEquals(0L, report.invalid)
            assertTrue(elapsedSeconds <= 12.0, "1 万行富化导入 ${"%.1f".format(elapsedSeconds)}s 超出 12s 预算（NFR-2 修订）")
        } finally {
            db.close()
        }
    }

    /** 全命中词典源：每行同构词条（2 释义 × 各 1 例句）——度量富化增量成本，不建大 Map。 */
    private object AllHitDictionaryProvider : DictionaryProvider {
        private val dict = DictionaryWord(
            text = "perf",
            ipaAm = "pɜːrf",
            ipaBr = "pɜːf",
            definitions = listOf(
                DictionaryDefinitionEntry(
                    partOfSpeech = "noun",
                    partOfSpeechOrder = 0,
                    definitionOrder = 0,
                    meaningEN = "a benchmark run",
                    meaningCN = "基准运行",
                    examples = listOf(DictionaryExample("The perf run passed.", "基准跑完了。")),
                ),
                DictionaryDefinitionEntry(
                    partOfSpeech = "verb",
                    partOfSpeechOrder = 1,
                    definitionOrder = 0,
                    meaningEN = "to measure speed",
                    meaningCN = "测速",
                    examples = listOf(DictionaryExample("We perf the engine.", "我们给引擎测速。")),
                ),
            ),
        )

        override suspend fun lookup(text: String): DictionaryWord = dict
    }

    @Test
    fun midImportCancellationRollsBackWithinOneSecond() {
        val db = TestDb.inMemory()
        val source = GeneratedLineSource(count = 100_000, gateAfter = 50_000) { "${alphaWord(it)}\t苹果" }
        val engine = ImportEngine(
            bytesSource = emptyBytesSource,
            lineSource = source,
            repository = SqlDelightImportRepository(db.database, Dispatchers.Default),
            eventBus = DefaultDomainEventBus(),
            clock = FixedClock(),
        )
        var error: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            try {
                engine.import(DetectedEncoding.Gb18030, ImportTarget.NewBook("取消基准"))
            } catch (t: Throwable) {
                error = t
            }
        }
        runBlocking { source.reachedGate.await() } // 已写入 5 万行后进入取消窗口

        val startNanos = System.nanoTime()
        runBlocking { job.cancelAndJoin() }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        try {
            assertIs<CancellationException>(error)
            assertTrue(elapsedMs <= 1_000, "取消回滚 ${elapsedMs}ms 超出 1s（NFR-2/TC-IMP-04）")
            assertEquals(0L, db.database.wordQueries.countAll().executeAsOne()) // 已写 5 万行整体回滚
            assertEquals(0, db.database.wordBookQueries.selectAllWordBooks().executeAsList().size) // 不建半成品本
        } finally {
            scope.cancel()
            db.close()
        }
    }

    /** 惰性生成行源（内存占用与行数无关）；[gateAfter] 后挂起等取消（取消注入点）。 */
    private class GeneratedLineSource(
        private val count: Int,
        private val gateAfter: Int? = null,
        private val lineFor: (Int) -> String,
    ) : TextLineSource {
        val reachedGate = CompletableDeferred<Unit>()

        override fun lines(encoding: DetectedEncoding): Flow<String> = flow {
            repeat(count) { i ->
                emit(lineFor(i))
                if (gateAfter != null && i + 1 == gateAfter) {
                    reachedGate.complete(Unit)
                    CompletableDeferred<Unit>().await() // 永不完成：仅靠协程取消解除
                }
            }
        }
    }
}
