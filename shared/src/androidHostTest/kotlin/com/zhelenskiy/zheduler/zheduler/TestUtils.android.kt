@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    // The framework driver rather than the bundled one: these tests run under Robolectric on the
    // host JVM, which cannot load the bundled driver's Android native library.
    val database = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
        .setDriver(AndroidSQLiteDriver())
        .build()
    return RoomTaskRepository(database, clock)
}

actual fun cleanupDatabaseTest() {
    // No cleanup needed for Android - in-memory databases die with their connection
}
