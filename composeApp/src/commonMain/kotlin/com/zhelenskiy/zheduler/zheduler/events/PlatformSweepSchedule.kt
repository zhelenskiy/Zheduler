@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Tell the operating system's own scheduler when this app next has something to do.
 *
 * Only Android has one that matters: there the process is killed as soon as the app leaves the
 * screen, and the schedule only survives because the system holds a wake-up for it. Every sweep
 * reports its next moment through here, so a task created in the app moves that wake-up
 * immediately — leaving it to the wake-up itself to notice meant a reminder set for five minutes
 * away went unheard behind one booked a day out.
 *
 * Elsewhere the process that swept is the same one that will sweep again, and there is nothing to
 * tell.
 */
expect fun reschedulePlatformSweep(nextAt: Instant?)
