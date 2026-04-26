@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock

private var testDb: ZhedulerRoomDatabase? = null

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    val db = Room.inMemoryDatabaseBuilder<ZhedulerRoomDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    testDb = db
    return RoomTaskRepository(db.zhedulerDao(), clock)
}

actual fun cleanupDatabaseTest() {
    testDb?.close()
    testDb = null
}
