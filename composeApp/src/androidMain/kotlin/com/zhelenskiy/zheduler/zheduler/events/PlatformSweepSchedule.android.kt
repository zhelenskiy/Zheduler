@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

actual fun reschedulePlatformSweep(nextAt: Instant?) =
    scheduleNextSweep(androidApplication(), nextAt)
