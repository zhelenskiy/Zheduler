package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSHomeDirectory
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

actual fun createScheduleStore(): ScheduleStore {
    val dataDir = "${NSHomeDirectory()}/Library/Application Support/Zheduler"
    val dirPath = Path(dataDir)
    if (!SystemFileSystem.exists(dirPath)) {
        SystemFileSystem.createDirectories(dirPath)
    }
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

actual fun createEventNotifier(): EventNotifier {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    center.requestAuthorizationWithOptions(
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
    ) { _, _ -> }

    return EventNotifier { alert ->
        val content = UNMutableNotificationContent().apply {
            setTitle(alert.title)
            setBody(alert.body)
        }
        // A null trigger delivers at once. The scheduling is the engine's, not the system's, so
        // that every platform decides when to fire in the same place.
        val request = UNNotificationRequest.requestWithIdentifier(alert.id, content, trigger = null)
        center.addNotificationRequest(request) { _ -> }
    }
}
