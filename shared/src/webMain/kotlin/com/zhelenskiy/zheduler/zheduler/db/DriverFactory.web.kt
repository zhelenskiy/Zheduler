package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

private fun createWebWorker(): Worker = js("""new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url))""")

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val driver = WebWorkerDriver(createWebWorker())

        // Enable foreign key constraints BEFORE creating schema
        // CRITICAL: PRAGMA foreign_keys must be set for EACH connection before any operations
        driver.await(null, "PRAGMA foreign_keys = ON;", 0)

        // Create schema if not exists
        ZhedulerDatabase.Schema.awaitCreate(driver)

        return driver
    }
}
