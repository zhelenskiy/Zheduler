package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate

/**
 * Not answered here.
 *
 * iOS has no equivalent of a service that simply keeps running: what it offers instead is
 * `startMonitoring(for:)`, where regions are handed to the system and the app is *woken* when one
 * is crossed. That is the right mechanism and a different shape from this one — it wants the areas
 * themselves, not a yes or no — and it cannot be tried on the machine this was written on, where
 * no iOS target can be run at all. Left undone rather than done blind.
 *
 * Until then the iOS build catches a crossing on its next sweep, the same way the web build does.
 */
actual fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds) = Unit

/** Nothing here ever asks where the device is, so there is no rate to set. */
actual fun updateLocationCheckRate(rate: LocationCheckRate) = Unit
