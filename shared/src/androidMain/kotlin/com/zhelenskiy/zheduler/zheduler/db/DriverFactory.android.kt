package com.zhelenskiy.zheduler.zheduler.db

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual suspend fun createDriver(): SqlDriver {
        // Enable foreign key constraints BEFORE creating schema
        val driver = AndroidSqliteDriver(
            ZhedulerDatabase.Schema.synchronous(),
            context,
            "zheduler.db",
            callback = object : AndroidSqliteDriver.Callback(ZhedulerDatabase.Schema.synchronous()) {
                override fun onOpen(db: app.cash.sqldelight.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Enable foreign key constraints (disabled by default in SQLite)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            }
        )
        return driver
    }
}
