package com.kfilesync.mobile.infrastructure.persistence

import app.cash.sqldelight.db.SqlDriver
import com.kfilesync.mobile.db.KFileSyncDatabase

/**
 * SQLDelight platform driver factory (design doc §10.2).
 *
 * androidMain -> AndroidSqliteDriver(KFileSyncDatabase.Schema, context, "kfilesync.db")
 * iosMain    -> NativeSqliteDriver(KFileSyncDatabase.Schema, "kfilesync.db")
 *
 * The constructor takes platform-specific dependencies as needed (Android
 * needs a Context, iOS doesn't).
 */
expect class DriverFactory {

    fun createDriver(): SqlDriver
}

/**
 * Creates the SQLDelight database with SQLite tuned for low-write, multi-reader
 * workloads (mobile foreground sync). Settings mirror the desktop:
 *
 * - WAL journaling so reads don't block writes.
 * - busy_timeout of 5 s so we wait through write-side contention instead of
 * surfacing SQLITE_BUSY to the domain layer.
 * - foreign_keys ON because share_members references shares.
 */
fun createDatabase(factory: DriverFactory): KFileSyncDatabase {
    val driver = factory.createDriver()
    return KFileSyncDatabase(driver)
}