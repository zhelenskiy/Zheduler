package com.zhelenskiy.zheduler.zheduler.di

import com.zhelenskiy.zheduler.zheduler.db.DriverFactory
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.coroutines.EmptyCoroutineContext

private val dbScope = CoroutineScope(EmptyCoroutineContext)

actual fun provideDeferredDatabase(): Deferred<ZhedulerDatabase> = dbScope.async {
    ZhedulerDatabase(DriverFactory().createDriver())
}