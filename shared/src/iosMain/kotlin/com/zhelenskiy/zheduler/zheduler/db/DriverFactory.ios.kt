package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            ZhedulerDatabase.Schema.synchronous(),
            "zheduler.db",
            onOpen = { connection ->
                // Enable foreign key constraints (disabled by default in SQLite)
                connection.prepare("PRAGMA foreign_keys = ON;").execute()
            }
        )
        return driver
    }
}
