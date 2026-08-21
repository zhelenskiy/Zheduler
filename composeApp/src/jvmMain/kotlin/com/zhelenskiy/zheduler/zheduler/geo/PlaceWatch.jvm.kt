package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine

/** Nothing to watch with: the desktop has no notion of where it is. */
actual fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds) = Unit
