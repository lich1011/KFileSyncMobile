package com.kfilesync.mobile.infrastructure.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.kfilesync.mobile.db.KFileSyncDatabase

/**
 * Android `actual` of [DriverFactory] - wraps [AndroidSqliteDriver] which
 * itself sits on top of the framework 'SQLiteOpenHelper' machinery.
 *
 * The [Context] is captured at module-load time (typically the
 * `androidContext()` provided by Koin) so we get the app's private data dir
 * for the database file.
 */
actual class DriverFactory(
    private val context: Context
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = KFileSyncDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            callback = object : AndroidSqliteDriver.Callback(KFileSyncDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                    db.enableWriteAheadLogging()
                }
            }
        )

    companion object {
        const val DATABASE_NAME: String = "kfilesync.db"
    }
}