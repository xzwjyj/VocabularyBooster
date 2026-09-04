package com.vocabularybooster

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vocabularybooster.db.VocabularyDatabase
import java.util.Properties
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TC-DB-07（TEST_PLAN §4.2，Phase 2 遗留、随 Phase 3 测试电池补齐）：
 * WordBook 类型与血缘的 **DB 级**约束断言——
 * ① `type` CHECK 生效：'ORIGINAL'|'DERIVED' 之外的插入值被 SQLite 拒绝；
 * ② 删除有派生子本的母本被 FK `ON DELETE RESTRICT` 拒绝（DATABASE_SCHEMA §2.4）；
 * ③ DERIVED 血缘 FK：parentWordBookId 悬挂（指向不存在的母本）被拒。
 *
 * FK 开启方式：生产端为 Android 驱动 `PRAGMA foreign_keys=ON`（DATABASE_SCHEMA §1）；
 * JVM JDBC 文件驱动每连接独立建立，故以等价连接属性传递（xerial SQLiteConfig 按
 * pragma 名消费 Properties，每次 getConnection 均应用）——多连接语义下比单条
 * PRAGMA 语句更接近生产配置。文件库承载，避免 in-memory 单连接实现细节。
 *
 * 「DERIVED 本 parentWordBookId/sourceSessionId 必填」在 schema v2 为应用层规则
 * （两列可空以容纳 ORIGINAL，决策 D3 设计；DB 级无可表达的 CHECK），由
 * deriveWordBook 恒写双血缘保证——断言见 LearningEngineDerivationIntegrationTest。
 * 本文件不改 schema、不新增 migration、不改生产语义（补齐边界，TEST_PLAN v1.3）。
 */
class WordBookTypeAndLineageConstraintTest {

    private val now = 1_760_000_000_000L

    /** FK 强制开启的 v2 文件库（关闭由测试函数末尾 driver.close() 完成）。 */
    private fun newFkEnabledDb(): Pair<VocabularyDatabase, JdbcSqliteDriver> {
        val path = createTempFile(prefix = "vb-tc-db07-", suffix = ".sqlite")
            .toFile().absolutePath.replace('\\', '/')
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:$path",
            properties = Properties().apply { put("foreign_keys", "true") },
        )
        VocabularyDatabase.Schema.create(driver)
        return VocabularyDatabase(driver) to driver
    }

    /** 约束违例断言：失败 + 消息含 "constraint"（驱动异常类型不耦合，同 createSession 物化测试约定）。 */
    private fun assertConstraintViolation(action: () -> Unit, because: String) {
        val outcome = runCatching(action)
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue(outcome.isFailure, because)
        assertTrue("constraint" in message.lowercase(), "应为约束违例，实际：$message")
    }

    @Test
    fun typeCheckRejectsValuesOutsideOriginalAndDerived() {
        val (db, driver) = newFkEnabledDb()
        try {
            assertEquals(2L, VocabularyDatabase.Schema.version) // 补齐断言上下文：schema v2 基线

            // 合法值对照组（生成 API 各走一条）
            db.wordBookQueries.insertOriginalWordBook("合法母本", null, now, now)
            db.learningSessionQueries.insertSession(1L, "ABANDONED", 10, now, now)
            db.wordBookQueries.insertDerivedWordBook(
                "合法母本 2025-10-09 08:53", null, 1L, 1L, now, now,
            )
            assertEquals(2L, db.wordBookQueries.selectAllWordBooks().executeAsList().size.toLong())

            // 非法 type：CHECK 拒绝（合法值集合是 schema 契约，无对应生成 API，走 raw SQL）
            assertConstraintViolation(
                { driver.execute(null, "INSERT INTO WordBook(type, name, createdAt, updatedAt) VALUES ('SHARED', 'x', $now, $now)", 0) },
                "type='SHARED' 应被 CHECK(type IN ('ORIGINAL','DERIVED')) 拒绝",
            )
            assertEquals(2L, db.wordBookQueries.selectAllWordBooks().executeAsList().size.toLong()) // 零写入
        } finally {
            driver.close()
        }
    }

    @Test
    fun deletingMotherWithDerivedChildIsRejectedByRestrict() {
        val (db, driver) = newFkEnabledDb()
        try {
            // 种子包进单一事务：JDBC 文件驱动每语句新建连接，事务外
            // last_insert_rowid() 只见空连接（同 LearningEngineDerivationIntegrationTest 约定）
            var motherId = 0L
            var childlessId = 0L
            var sessionId = 0L
            var derivedId = 0L
            db.transaction {
                db.wordBookQueries.insertOriginalWordBook("母本", null, now, now)
                motherId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
                db.wordBookQueries.insertOriginalWordBook("无子本", null, now, now)
                childlessId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
                // 派生子本（sourceSessionId 的 FK 需要一条会话行）
                db.learningSessionQueries.insertSession(motherId, "ABANDONED", 10, now, now)
                sessionId = db.learningSessionQueries.selectLastInsertRowId().executeAsOne()
                db.wordBookQueries.insertDerivedWordBook("母本 2025-10-09 08:53", null, motherId, sessionId, now, now)
                derivedId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
            }

            // RESTRICT：血缘完整——先处理子本，才允许删母本
            assertConstraintViolation(
                { db.wordBookQueries.deleteWordBook(motherId) },
                "删除有派生子本的母本应被 FK ON DELETE RESTRICT 拒绝",
            )
            assertEquals("母本", db.wordBookQueries.selectWordBookById(motherId).executeAsOne().name) // 母本仍在

            // 对照组 A：无子本的本正常删除（证明拒绝源自血缘，非 FK 配置误伤）
            db.wordBookQueries.deleteWordBook(childlessId)
            // 对照组 B：LearningSession(wordBookId) 与 WordBook.sourceSessionId 亦是
            // RESTRICT 子行链（会话=学习历史，无生产删除查询 by design）。归因脚手架：
            // raw UPDATE 置空派生行 sourceSessionId（可空列）→ 清会话行 → 母本仍被拒，
            // 证明 RESTRICT 拒绝独立来自派生子本；再删子本 → 母本可删（逃生顺序）
            driver.execute(null, "UPDATE WordBook SET sourceSessionId = NULL WHERE wordBookId = $derivedId", 0)
            driver.execute(null, "DELETE FROM LearningSession WHERE sessionId = $sessionId", 0)
            assertConstraintViolation(
                { db.wordBookQueries.deleteWordBook(motherId) },
                "仅剩派生子本时删除母本仍应被 FK 拒绝",
            )
            db.wordBookQueries.deleteWordBook(derivedId)
            db.wordBookQueries.deleteWordBook(motherId)
            assertEquals(0, db.wordBookQueries.selectAllWordBooks().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun derivedLineageFkRejectsDanglingParent() {
        val (_, driver) = newFkEnabledDb()
        try {
            assertConstraintViolation(
                {
                    driver.execute(
                        null,
                        "INSERT INTO WordBook(type, name, parentWordBookId, sourceSessionId, createdAt, updatedAt)" +
                            " VALUES ('DERIVED', '悬挂血缘', 999, NULL, $now, $now)",
                        0,
                    )
                },
                "DERIVED 本 parentWordBookId 指向不存在的母本应被 FK 拒绝",
            )
        } finally {
            driver.close()
        }
    }
}
