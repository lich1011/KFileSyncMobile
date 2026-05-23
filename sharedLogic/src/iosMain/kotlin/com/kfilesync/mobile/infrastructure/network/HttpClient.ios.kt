package com.kfilesync.mobile.infrastructure.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.kfilesync.mobile.db.KFileSyncDatabase

/**
 * iOS `actual` of [DriverFactory] - backed by [NativeSqliteDriver] which
 * statically links against the SQLite shipped with Darwin.
 *
 * The database file is placed in the app sandbox under `Library/Application Support`
 * by NativeSqliteDriver, which matches Apple's guidance for non-user-visible
 * persistent state.
 */
actual class DriverFactory {

    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(
            schema = KFileSyncDatabase.Schema,
            name = DATABASE_NAME
        )

    companion object {
        const val DATABASE_NAME: String = "kfilesync.db"
    }
}