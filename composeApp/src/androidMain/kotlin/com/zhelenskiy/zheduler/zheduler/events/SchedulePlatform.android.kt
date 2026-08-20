@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import ca.gosyer.appdirs.AppDirs
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import java.io.File
import kotlin.time.ExperimentalTime

actual fun createScheduleStore(): ScheduleStore {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

/** The channel tasks are announced on. Created on first use; creating it again is a no-op. */
const val TASK_NOTIFICATION_CHANNEL_ID = "zheduler.tasks"

actual fun createEventNotifier(): EventNotifier = AndroidEventNotifier(androidApplication())

/**
 * Posts to the system notification shade.
 *
 * From API 33 the user may have refused the permission, in which case `notify` is ignored rather
 * than failing — the schedule still advances, which is what matters for the task's own state.
 */
class AndroidEventNotifier(private val context: Context) : EventNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    override suspend fun post(alert: TaskAlert) {
        manager.createNotificationChannel(
            NotificationChannel(
                TASK_NOTIFICATION_CHANNEL_ID,
                "Task reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val notification = Notification.Builder(context, TASK_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setWhen(alert.at.toEpochMilliseconds())
            .build()

        // The key, not a counter: a redelivery of the same event replaces its notification instead
        // of stacking a second copy.
        manager.notify(alert.id, alert.id.hashCode(), notification)
    }
}
