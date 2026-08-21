package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import platform.Foundation.NSHomeDirectory
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

private val soundsDirectory get() = "${NSHomeDirectory()}/Library/Sounds"

actual fun createScheduleStore(): ScheduleStore {
    val dataDir = "${NSHomeDirectory()}/Library/Application Support/Zheduler"
    val dirPath = Path(dataDir)
    if (!SystemFileSystem.exists(dirPath)) {
        SystemFileSystem.createDirectories(dirPath)
    }
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

actual fun createNotificationSettingsStore(): KStore<NotificationSettings> {
    val dataDir = "${NSHomeDirectory()}/Library/Application Support/Zheduler"
    val dirPath = Path(dataDir)
    if (!SystemFileSystem.exists(dirPath)) {
        SystemFileSystem.createDirectories(dirPath)
    }
    return storeOf(Path("$dataDir/notification_settings.json"), default = NotificationSettings())
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
            soundFor(alert.sound)?.let { setSound(it) }
        }
        // A null trigger delivers at once. The scheduling is the engine's, not the system's, so
        // that every platform decides when to fire in the same place.
        val request = UNNotificationRequest.requestWithIdentifier(alert.id, content, trigger = null)
        center.addNotificationRequest(request) { _ -> }
    }
}

/**
 * The sound to attach, or `null` to leave the notification silent.
 *
 * A tone of the app's own is named rather than played: iOS plays the sound itself, and looks for
 * the name in `Library/Sounds` inside the app's container, so the file is put there the first time
 * it is asked for. Naming a file that is not there gets the default sound, not silence, so a
 * failed copy is merely the wrong tone.
 */
private suspend fun soundFor(sound: NotificationSound): UNNotificationSound? = when (sound) {
    NotificationSound.Silent -> null
    // iOS offers an app the default notification sound or one of its own files, and nothing that
    // belongs to the system — there is no alarm tone to ask for, so an alarm takes the default.
    NotificationSound.Default, NotificationSound.Alarm -> UNNotificationSound.defaultSound
    NotificationSound.Chime, NotificationSound.Bell -> {
        val file = "${sound.bundledName}.wav"
        if (installTone(sound, file)) UNNotificationSound.soundNamed(file) else UNNotificationSound.defaultSound
    }
}

/** The tones already known to be in `Library/Sounds` this run. */
private val installed = mutableMapOf<NotificationSound, Boolean>()
private val installing = Mutex()

/** Copies a bundled tone into `Library/Sounds` unless the copy there is already the same file. */
private suspend fun installTone(sound: NotificationSound, file: String): Boolean = installing.withLock {
    // A tone already put there is not looked at again this run: nothing moves it under a running
    // app, and the alternative is reading the file through for every notification that uses it. A
    // copy that failed is not remembered, so whatever stopped it gets another chance.
    installed[sound]?.let { return@withLock it }
    val target = Path("$soundsDirectory/$file")
    val bytes = readBundledTone(sound) ?: return@withLock false
    // Against the file's contents rather than its presence: a tone the app has since replaced
    // would otherwise never reach a device that had copied the old one.
    if (runCatching { SystemFileSystem.source(target).buffered().use { it.readByteArray() } }
            .getOrNull()
            ?.contentEquals(bytes) == true
    ) {
        installed[sound] = true
        return@withLock true
    }
    val written = runCatching {
        val directory = Path(soundsDirectory)
        if (!SystemFileSystem.exists(directory)) SystemFileSystem.createDirectories(directory)
        // Written beside the target and moved into place: iOS reads the file when it comes to play
        // the notification, and a rename has either happened or not.
        val staging = Path("$soundsDirectory/$file.part")
        SystemFileSystem.sink(staging).buffered().use { it.write(bytes) }
        SystemFileSystem.atomicMove(staging, target)
    }.isSuccess
    if (written) installed[sound] = true
    written
}
