@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private var index = 0

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    index++
    val schema = ZhedulerDatabase.Schema.synchronous()
    val driver = NativeSqliteDriver(
        DatabaseConfiguration(
            name = "test-$index.db",
            version = schema.version.toInt(),
            create = { connection ->
                wrapConnection(connection) { schema.create(it) }
            },
            upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) {
                    schema.migrate(it, oldVersion.toLong(), newVersion.toLong())
                }
            },
            inMemory = true
        )
    )
    // Enable foreign key constraints (disabled by default in SQLite)
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    return SqlDelightTaskRepository(ZhedulerDatabase(driver), clock)
}

actual fun cleanupDatabaseTest() {
    // No cleanup needed for iOS - drivers are garbage collected
}