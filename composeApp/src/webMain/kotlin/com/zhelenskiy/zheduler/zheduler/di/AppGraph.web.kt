package com.zhelenskiy.zheduler.zheduler.di

import androidx.room3.Room
import com.zhelenskiy.zheduler.worker.createSQLiteWasmWorker
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerRoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlin.coroutines.EmptyCoroutineContext

private val dbScope = CoroutineScope(EmptyCoroutineContext)

actual fun provideDeferredDatabase(): Deferred<ZhedulerRoomDatabase> = dbScope.async {
    Room.databaseBuilder<ZhedulerRoomDatabase>("Zheduler.db")
        .setDriver(createSQLiteWasmWorker())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}
