package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate

/**
 * A tab watches while it is open and not a moment longer, whatever it is told.
 *
 * The loop that sweeps is already running for as long as the page is, and it asks where the device
 * is on every pass; there is nothing further to start. A crossing made while the tab was shut is
 * found when it is opened again, by the same comparison against remembered whereabouts that finds
 * one made while a phone was asleep.
 */
actual fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds) = Unit

/** Nothing here ever asks where the device is, so there is no rate to set. */
actual fun updateLocationCheckRate(rate: LocationCheckRate) = Unit
