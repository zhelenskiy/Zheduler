package com.zhelenskiy.zheduler.zheduler.di

import android.app.Application
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import com.zhelenskiy.zheduler.zheduler.db.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.coroutines.EmptyCoroutineContext

private lateinit var appContext: Application

fun initAndroidDependencies(application: Application) {
    appContext = application
}

/**
 * The application, for the parts of the app that need one and are not started by an activity.
 *
 * Must be called after [initAndroidDependencies], which `ZhedulerApplication.onCreate` does — every
 * entry point the system can start, a worker or a boot receiver included, runs after that.
 */
fun androidApplication(): Application = appContext

private val dbScope = CoroutineScope(EmptyCoroutineContext)
actual fun provideDeferredDatabase(): Deferred<ZhedulerDatabase> = dbScope.async {
    DatabaseFactory(appContext).createDatabase()
}
