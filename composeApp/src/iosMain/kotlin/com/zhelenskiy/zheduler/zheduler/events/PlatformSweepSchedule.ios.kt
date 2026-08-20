@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Nothing to tell: the loop that swept is still running and will sweep again itself. */
actual fun reschedulePlatformSweep(nextAt: Instant?) = Unit
