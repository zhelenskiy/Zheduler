@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A message to put in front of the user, already worded.
 *
 * [id] is the event key it came from, so a platform that can replace its own notifications can
 * recognise a repeat rather than stacking one.
 */
data class TaskAlert(
    val id: String,
    val taskId: String,
    val spaceId: String,
    val title: String,
    val body: String,
    val at: Instant,
    val sound: NotificationSound = NotificationSound.Default,
)

/**
 * Where alerts go. Each platform answers this with whatever it has — a notification channel, a
 * tray icon, the browser's notification API — and tests answer it with a list.
 */
fun interface EventNotifier {
    suspend fun post(alert: TaskAlert)
}

/** Drops everything, for platforms and builds with nowhere to put an alert. */
object NoOpEventNotifier : EventNotifier {
    override suspend fun post(alert: TaskAlert) = Unit
}
