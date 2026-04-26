@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Worker
import kotlin.time.Clock

private var testDb: ZhedulerRoomDatabase? = null
private var testIndex = 0

private fun createTestWorker(): Worker = js("""new Worker(new URL("sqlite-web-worker/worker.js", import.meta.url))""")

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    testIndex++
    val db = Room.databaseBuilder<ZhedulerRoomDatabase>(
        name = "test-$testIndex.db"
    )
        .setDriver(WebWorkerSQLiteDriver(createTestWorker()))
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    testDb = db
    return RoomTaskRepository(db.zhedulerDao(), clock)
}

actual fun cleanupDatabaseTest() {
    testDb?.close()
    testDb = null
}
