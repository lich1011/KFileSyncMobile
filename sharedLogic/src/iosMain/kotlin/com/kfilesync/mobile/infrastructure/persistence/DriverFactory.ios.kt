package com.kfilesync.mobile.infrastructure.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.kfilesync.mobile.db.KFileSyncDatabase

/**
 * iOS `actual` of [DriverFactory] - wraps [NativeSqliteDriver].
 */
actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = KFileSyncDatabase.Schema,
            name = DATABASE_NAME
        )
        // iOS NativeSqliteDriver doesn't have the same callback structure as 
        // Android, so we apply pragmas manually here.
        driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        driver.execute(null, "PRAGMA synchronous = NORMAL;", 0)
        driver.execute(null, "PRAGMA busy_timeout = 5000;", 0)
        return driver
    }

    companion object {
        const val DATABASE_NAME: String = "kfilesync.db"
    }
}
