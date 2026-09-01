package com.vocabularybooster.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * 存储驱动端口（ARCHITECTURE §5）。
 * Android actual：`AndroidSqliteDriver`（PRAGMA foreign_keys=ON，DATABASE_SCHEMA §1）；
 * iOS actual：`NSqliteDriver`。
 */
public interface DatabaseDriverFactoryProvider {
    public fun create(): SqlDriver
}
