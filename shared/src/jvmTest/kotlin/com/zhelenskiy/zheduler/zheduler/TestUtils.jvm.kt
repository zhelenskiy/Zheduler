@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ZhedulerDatabase.Schema.awaitCreate(driver)
    // Enable foreign key constraints (disabled by default in SQLite)
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    return SqlDelightTaskRepository(ZhedulerDatabase(driver), clock)
}

actual fun cleanupDatabaseTest() {
    // No cleanup needed for JVM - drivers are garbage collected
}
