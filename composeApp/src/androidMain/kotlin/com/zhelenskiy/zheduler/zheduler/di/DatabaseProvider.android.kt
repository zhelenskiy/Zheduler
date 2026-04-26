package com.zhelenskiy.zheduler.zheduler.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.coroutines.EmptyCoroutineContext

private lateinit var appContext: Application

fun initAndroidDependencies(application: Application) {
    appContext = application
}

private val dbScope = CoroutineScope(EmptyCoroutineContext)
actual fun provideDeferredDatabase(): Deferred<ZhedulerRoomDatabase> = dbScope.async {
    // One-time cleanup: delete old SQLDelight database
    appContext.deleteDatabase("zheduler.db")
    val dbFile = appContext.getDatabasePath("zheduler_room.db")
    Room.databaseBuilder<ZhedulerRoomDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
