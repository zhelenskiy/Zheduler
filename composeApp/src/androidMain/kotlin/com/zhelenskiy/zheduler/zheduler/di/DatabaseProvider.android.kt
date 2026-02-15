package com.zhelenskiy.zheduler.zheduler.di

import android.app.Application
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import com.zhelenskiy.zheduler.zheduler.db.DriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.coroutines.EmptyCoroutineContext

private lateinit var appContext: Application

fun initAndroidDependencies(application: Application) {
    appContext = application
}

private val dbScope = CoroutineScope(EmptyCoroutineContext)
actual fun provideDeferredDatabase(): Deferred<ZhedulerDatabase> = dbScope.async {
    ZhedulerDatabase(DriverFactory(appContext).createDriver())
}
