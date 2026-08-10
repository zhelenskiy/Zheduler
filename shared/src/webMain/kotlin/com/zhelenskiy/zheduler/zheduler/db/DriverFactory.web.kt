package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.createDefaultWebWorkerDriver

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val driver = createDefaultWebWorkerDriver()

        // Enable foreign key constraints BEFORE creating schema
        // CRITICAL: PRAGMA foreign_keys must be set for EACH connection before any operations
        driver.await(null, "PRAGMA foreign_keys = ON;", 0)

        // Create schema if not exists
        ZhedulerDatabase.Schema.awaitCreate(driver)

        return driver
    }
}
