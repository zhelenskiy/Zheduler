@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import androidx.room3.Room
import com.zhelenskiy.zheduler.worker.createSQLiteWasmWorker
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One worker for the whole suite. Every [Room.inMemoryDatabaseBuilder] opens its own independent
 * `:memory:` database inside it, so tests stay isolated — but a driver per test would leave behind
 * a live Worker each time (the driver has no terminate), and a few hundred SQLite WASM heaps is
 * enough to take the browser down mid-run.
 */
private val sharedDriver by lazy { createSQLiteWasmWorker() }

actual suspend fun createDatabaseRepository(clock: Clock): TaskRepository {
    // In-memory: the worker only reaches for OPFS when handed a real file name, so the browser
    // suites need neither cross-origin isolation nor cleanup of persisted files.
    val database = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
        .setDriver(sharedDriver)
        .build()

    return RoomTaskRepository(database, clock)
}

actual fun cleanupDatabaseTest() {
    // Deliberately a no-op. `RoomDatabase.close()` spin-waits (`CloseBarrier`) until Room's
    // in-flight coroutines finish; on the JVM another thread finishes them while it spins, but a
    // browser has a single thread, so closing a database whose invalidation job is still running
    // wedges the page for good (karma reports it as a ping timeout). The databases are in-memory
    // and the page is torn down at the end of the run, so leaving them open costs nothing here.
}
