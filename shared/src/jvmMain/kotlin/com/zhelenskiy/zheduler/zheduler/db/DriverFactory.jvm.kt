package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val dbFile = File(System.getProperty("user.home"), ".zheduler/zheduler.db")
        dbFile.parentFile?.mkdirs()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        // Enable foreign keys (disabled by default in SQLite)
        driver.await(null, "PRAGMA foreign_keys = ON;", 0)

        // Create schema if not exists
        ZhedulerDatabase.Schema.awaitCreate(driver)

        return driver
    }
}
