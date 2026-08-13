package com.zhelenskiy.zheduler.worker

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

/**
 * Driver backed by the SQLite WASM worker in this module's local npm package.
 *
 * Databases opened with a file name live in the Origin Private File System and survive reloads;
 * the ":memory:" name Room uses for [androidx.room3.Room.inMemoryDatabaseBuilder] stays transient.
 */
expect fun createSQLiteWasmWorker(): WebWorkerSQLiteDriver
