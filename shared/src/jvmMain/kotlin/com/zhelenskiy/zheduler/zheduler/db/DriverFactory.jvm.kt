package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val dbFile = File(System.getProperty("user.home"), ".zheduler/zheduler.db")
        dbFile.parentFile?.mkdirs()

        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            properties = Properties().apply {
                // Enable foreign keys (disabled by default in SQLite)
                put("foreign_keys", "true")
            },
            schema = ZhedulerDatabase.Schema.synchronous(),
        )

        return driver
    }
}
