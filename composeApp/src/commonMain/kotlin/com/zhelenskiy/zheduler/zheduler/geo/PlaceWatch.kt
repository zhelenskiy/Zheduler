package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate

/**
 * Tells the platform whether anything is still waiting on a place.
 *
 * Sweeping alone only ever *samples* where the device is, and a boundary is not a state but a
 * moment: a user who leaves the office and comes back between two sweeps has crossed it twice, and
 * a sweep comparing where they are now against where they were last seen finds no change at all. A
 * platform that can be told to watch continuously should be, and only for as long as something is
 * waiting — watching where a device goes costs battery, and nobody should pay for it on a database
 * with no such rule in it.
 *
 * Answered properly on Android, where it runs a foreground service the system will not kill.
 * Elsewhere it does nothing: the browser stops when its tab does, and the desktop has no notion of
 * where it is.
 */
expect fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds)


/**
 * What the user has said about how often to ask where the device is.
 *
 * Told to the platform so a change takes hold at once, rather than at the next thing that happens
 * to read the setting. The watch keeps its own copy for the process — it can be started by a boot,
 * with no composition anywhere — and reads the stored one itself the first time it sweeps, so a
 * choice made before a reboot is not quietly the default afterwards.
 *
 * Does nothing on the platforms that never ask where the device is.
 */
expect fun updateLocationCheckRate(rate: LocationCheckRate)

/** What a change in what is being watched should do to the watch that is running. */
enum class WatchAction { Start, Stop, LeaveAlone }

/**
 * Whether to start the watch, stop it, or leave it be.
 *
 * Here rather than beside the service that acts on it, because it is the one part with no Android
 * in it and the one part that can be got wrong quietly: a watch that fails to start is a rule that
 * does not fire, and a watch that fails to stop is a permanent notification and a radio running
 * for nothing until the process dies.
 *
 * [signature] identifies what a watch was started to do — which permissions it holds, how often it
 * asks. Stopping does not consult it: the signature can be cleared while the service is still up
 * (changing the check rate does exactly that, so the next sweep re-registers), and a comparison
 * that then matched would read "nothing to do" at the very moment the answer was "stop".
 */
fun watchAction(wanted: Boolean, running: Boolean, signature: Int, startedWith: Int): WatchAction =
    when {
        !wanted -> if (running) WatchAction.Stop else WatchAction.LeaveAlone
        signature != startedWith -> WatchAction.Start
        else -> WatchAction.LeaveAlone
    }
