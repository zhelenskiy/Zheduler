package com.zhelenskiy.zheduler.zheduler.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

private val dbScope = CoroutineScope(EmptyCoroutineContext)

actual fun provideDeferredDatabase(): Deferred<ZhedulerRoomDatabase> = dbScope.async {
    val dbDir = File(System.getProperty("user.home"), ".zheduler")
    dbDir.mkdirs()
    val dbFile = File(dbDir, "zheduler_room.db")
    // One-time cleanup: delete old SQLDelight database
    val oldDbFile = File(dbDir, "zheduler.db")
    if (oldDbFile.exists()) {
        oldDbFile.delete()
        File(oldDbFile.path + "-wal").delete()
        File(oldDbFile.path + "-shm").delete()
    }
    Room.databaseBuilder<ZhedulerRoomDatabase>(
        name = dbFile.absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
