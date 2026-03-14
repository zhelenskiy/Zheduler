package com.zhelenskiy.zheduler.zheduler.db

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

actual class DriverFactory(private val context: Context) {
    actual suspend fun createDriver(): SqlDriver {
        // Use RequerySQLiteOpenHelperFactory to get SQLite with JSON1 extension support
        val driver = AndroidSqliteDriver(
            schema = ZhedulerDatabase.Schema.synchronous(),
            context = context,
            name = "zheduler.db",
            factory = RequerySQLiteOpenHelperFactory()
        )
        // Enable foreign key constraints (disabled by default in SQLite)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        return driver
    }
}
