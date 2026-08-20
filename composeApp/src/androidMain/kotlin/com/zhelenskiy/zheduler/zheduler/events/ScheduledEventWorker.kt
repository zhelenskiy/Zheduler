@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhelenskiy.zheduler.zheduler.di.obtainAppGraph
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * One sweep, then an appointment to come back for the next one.
 *
 * This is what makes the schedule outlive the app. WorkManager keeps its queue in its own
 * database, so an appointment made here survives the process being killed, the app being swapped
 * out of memory, and the device being restarted — the system re-registers persisted work at boot
 * and this worker runs without anything of ours having been started first.
 *
 * The re-appointment is made *by the worker itself* rather than by a repeating schedule, because
 * the interesting moment is rarely a fixed distance away: it is whenever the next reminder,
 * deadline or recurrence happens to fall.
 */
class ScheduledEventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        obtainAppGraph().scheduledEventEngine.sweep()
        // The engine re-books the next one itself, through the hook it is given — including after
        // sweeps made while the app is open, which is the only way a task created just now can be
        // heard about before whatever was booked previously comes round.
        Result.success()
    } catch (_: Throwable) {
        // Retry, never failure: the chain is re-booked by a *successful* sweep, so a run that ends
        // here without asking to come back again is the end of background notifications until the
        // user next opens the app. A database locked by the foreground process is enough to get
        // here, and is entirely temporary.
        Result.retry()
    }
}

/** The single queued sweep. Naming it means a new appointment replaces the old one. */
private const val SWEEP_WORK_NAME = "zheduler.scheduled-events"

/**
 * Ask for a sweep at [nextAt], or as soon as the system will allow if that is `null` or past.
 *
 * The wait is capped: with nothing scheduled the worker still looks in once a day, which is what
 * notices a change of zone on a device that was asleep for it, and what re-establishes the chain
 * if an appointment was ever dropped.
 *
 * Timing is the system's to decide. WorkManager will not wake a dozing device to the second, so a
 * reminder can arrive a few minutes late; the alternative is an exact alarm, which recent Android
 * versions grant only to apps whose whole purpose is alarms.
 */
fun scheduleNextSweep(context: Context, nextAt: Instant?, now: Instant = Clock.System.now()) {
    val delay = (nextAt?.minus(now) ?: MAX_IDLE_WAIT).coerceIn(Duration.ZERO, MAX_IDLE_WAIT)
    enqueue(context, delay, ExistingWorkPolicy.REPLACE)
}

/** Sweep at the first opportunity, displacing whatever was booked. For boot and clock changes. */
fun sweepNow(context: Context) = enqueue(context, Duration.ZERO, ExistingWorkPolicy.REPLACE)

/** Book a sweep unless one is already booked. For startup, which must not disturb a live schedule. */
fun ensureSweepScheduled(context: Context) = enqueue(context, STARTUP_GRACE, ExistingWorkPolicy.KEEP)

private fun enqueue(context: Context, delay: Duration, policy: ExistingWorkPolicy) {
    val request = OneTimeWorkRequestBuilder<ScheduledEventWorker>()
        .setInitialDelay(delay.toJavaDuration())
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(SWEEP_WORK_NAME, policy, request)
}

private val MAX_IDLE_WAIT = 1.days
private val STARTUP_GRACE = 5.seconds
