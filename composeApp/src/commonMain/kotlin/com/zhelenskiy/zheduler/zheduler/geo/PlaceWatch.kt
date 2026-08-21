package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine

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
