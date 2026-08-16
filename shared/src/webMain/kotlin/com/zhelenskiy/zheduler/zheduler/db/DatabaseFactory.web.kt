package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import com.zhelenskiy.zheduler.worker.createSQLiteWasmWorker

actual class DatabaseFactory {
    /**
     * Named — not in-memory — so the worker stores the database in the Origin Private File System
     * and it survives reloads. The page must be cross-origin isolated for that; see the COOP/COEP
     * headers in `composeApp/webpack.config.d`.
     */
    actual fun createDatabase(): ZhedulerDatabase =
        Room.databaseBuilder<ZhedulerDatabase>(name = DATABASE_FILE_NAME)
            .setDriver(createSQLiteWasmWorker())
            .withZhedulerMigrations()
            .build()
}
