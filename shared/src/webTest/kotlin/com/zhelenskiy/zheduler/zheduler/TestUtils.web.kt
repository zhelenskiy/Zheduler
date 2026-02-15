@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.zhelenskiy.zheduler.zheduler.db.DriverFactory
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock

// Track all drivers created for cleanup
internal val webTestDrivers = mutableListOf<SqlDriver>()

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    val driver = DriverFactory().createDriver()

    // Track this driver for cleanup
    webTestDrivers.add(driver)

    return SqlDelightTaskRepository(ZhedulerDatabase(driver), clock)
}

actual fun cleanupDatabaseTest() {
    // Close all drivers created during this test
    webTestDrivers.forEach { driver ->
        try {
            driver.close()
        } catch (e: Exception) {
            // Ignore errors during cleanup
            logCleanupError(e)
        }
    }
    webTestDrivers.clear()
}

// Platform-specific logging
internal expect fun logCleanupError(e: Exception)
