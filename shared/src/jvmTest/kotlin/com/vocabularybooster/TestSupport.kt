package com.vocabularybooster

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vocabularybooster.db.VocabularyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.io.path.createTempFile

/** 测试用派发器：真实 JDBC 直连，无需 IO 线程池。 */
internal val DispatchersForTest: CoroutineDispatcher = Dispatchers.Unconfined

/** 可推进的固定时钟（铁律 10：引擎内时间一律注入）。 */
internal class FixedClock(private var nowMillis: Long = 1_760_000_000_000L) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    fun advanceMillis(delta: Long) {
        nowMillis += delta
    }
}

/** JVM 测试库句柄：内存库 / 文件库（持久化与迁移测试用）。 */
internal class TestDb(
    val database: VocabularyDatabase,
    val driver: JdbcSqliteDriver,
    val path: String?,
) {
    fun close() = driver.close()

    companion object {
        fun inMemory(): TestDb {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            VocabularyDatabase.Schema.create(driver)
            return TestDb(VocabularyDatabase(driver), driver, null)
        }

        /** 新建文件库（建 schema），返回句柄；重开用 [fileExisting]。 */
        fun file(): TestDb {
            val path = createTempFile(prefix = "vb-test-", suffix = ".sqlite")
                .toFile().absolutePath.replace('\\', '/')
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
            VocabularyDatabase.Schema.create(driver)
            return TestDb(VocabularyDatabase(driver), driver, path)
        }

        fun fileExisting(path: String, createSchema: Boolean = false): TestDb {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
            if (createSchema) VocabularyDatabase.Schema.create(driver)
            return TestDb(VocabularyDatabase(driver), driver, path)
        }
    }
}
