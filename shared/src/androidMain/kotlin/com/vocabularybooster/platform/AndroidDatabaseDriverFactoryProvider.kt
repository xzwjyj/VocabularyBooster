package com.vocabularybooster.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.vocabularybooster.db.VocabularyDatabase

/**
 * DatabaseDriverFactoryProvider 的 Android 实现。
 *
 * DATABASE_SCHEMA §1：SQLite 默认关闭外键，Android 驱动必须在每次打开连接时
 * `PRAGMA foreign_keys = ON`（强制开启，违反即 bug）。
 */
public class AndroidDatabaseDriverFactoryProvider(
    private val context: Context,
) : DatabaseDriverFactoryProvider {
    override fun create(): SqlDriver {
        return AndroidSqliteDriver(
            schema = VocabularyDatabase.Schema,
            context = context,
            name = "vocabulary.db",
            callback = object : AndroidSqliteDriver.Callback(VocabularyDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys=ON;")
                }
            },
        )
    }
}
