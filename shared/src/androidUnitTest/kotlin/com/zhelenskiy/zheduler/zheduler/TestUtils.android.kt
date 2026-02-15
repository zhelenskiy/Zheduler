@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val driver: SqlDriver = AndroidSqliteDriver(ZhedulerDatabase.Schema.synchronous(), context, null)
    // Enable foreign key constraints (disabled by default in SQLite)
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    return SqlDelightTaskRepository(ZhedulerDatabase(driver), clock)
}

actual fun cleanupDatabaseTest() {
    // No cleanup needed for Android - drivers are garbage collected
}
