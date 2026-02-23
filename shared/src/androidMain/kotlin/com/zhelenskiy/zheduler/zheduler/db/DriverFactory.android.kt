package com.zhelenskiy.zheduler.zheduler.db

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual suspend fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(ZhedulerDatabase.Schema.synchronous(), context, "zheduler.db")
        // Enable foreign key constraints (disabled by default in SQLite)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        return driver
    }
}
