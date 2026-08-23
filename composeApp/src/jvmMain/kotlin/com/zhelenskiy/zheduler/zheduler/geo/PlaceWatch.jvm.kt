package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate

/** Nothing to watch with: the desktop has no notion of where it is. */
actual fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds) = Unit

/** Nothing here ever asks where the device is, so there is no rate to set. */
actual fun updateLocationCheckRate(rate: LocationCheckRate) = Unit
