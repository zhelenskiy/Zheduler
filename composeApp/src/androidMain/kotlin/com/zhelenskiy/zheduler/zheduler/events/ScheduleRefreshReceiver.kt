package com.zhelenskiy.zheduler.zheduler.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the schedule back on its feet after the things that invalidate it.
 *
 * **Boot.** The user expects reminders from the moment the phone is on, without having opened the
 * app since. WorkManager does restore its own queue at boot, so this is a second line rather than
 * the only one — but it is the line that covers a queue that was never established, or was dropped
 * because the app was force-stopped.
 *
 * **A change of zone or of the clock.** Every reminder is worked out in the current zone, so
 * flying somewhere moves them all. Sweeping straight away recomputes them and re-books the next
 * appointment for the moment that is now correct.
 *
 * **An upgrade.** Replacing the package clears the app's alarms; this puts them back.
 */
class ScheduleRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
                -> sweepNow(context.applicationContext)
        }
    }
}
