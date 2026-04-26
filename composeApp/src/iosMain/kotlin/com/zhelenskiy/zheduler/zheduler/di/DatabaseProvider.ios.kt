package com.zhelenskiy.zheduler.zheduler.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.EmptyCoroutineContext

private val dbScope = CoroutineScope(EmptyCoroutineContext)

actual fun provideDeferredDatabase(): Deferred<ZhedulerRoomDatabase> = dbScope.async {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val docPath = requireNotNull(documentDirectory?.path)
    // One-time cleanup: delete old SQLDelight database
    val fileManager = NSFileManager.defaultManager
    val oldDbPath = "$docPath/zheduler.db"
    if (fileManager.fileExistsAtPath(oldDbPath)) {
        fileManager.removeItemAtPath(oldDbPath, null)
        fileManager.removeItemAtPath("$oldDbPath-wal", null)
        fileManager.removeItemAtPath("$oldDbPath-shm", null)
    }
    val dbFilePath = "$docPath/zheduler_room.db"
    Room.databaseBuilder<ZhedulerRoomDatabase>(
        name = dbFilePath
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
