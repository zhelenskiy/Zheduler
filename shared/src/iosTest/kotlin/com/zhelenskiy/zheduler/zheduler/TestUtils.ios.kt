@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    val database = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    return RoomTaskRepository(database, clock)
}

actual fun cleanupDatabaseTest() {
    // No cleanup needed for iOS - in-memory databases die with their connection
}
