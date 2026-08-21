package com.zhelenskiy.zheduler.zheduler.events

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

actual fun createScheduleStore(): ScheduleStore {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

actual fun createNotificationSettingsStore(): KStore<NotificationSettings> {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return storeOf(Path("$dataDir/notification_settings.json"), default = NotificationSettings())
}

/**
 * The desktop's own notifications, by whichever route this desktop has.
 *
 * The tray balloon looks like the portable answer and is not: on macOS `TrayIcon.displayMessage`
 * has never put anything on screen, so an icon appeared in the menu bar and every reminder went
 * nowhere. macOS is asked through its own notification service instead, and the balloon is left to
 * the desktops where it works.
 */
actual fun createEventNotifier(): EventNotifier =
    if (isMacOs) MacNotificationCentre else TrayBalloon

internal val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * `display notification` through osascript — the supported way for a program that is not a bundled
 * .app to reach Notification Centre.
 */
internal object MacNotificationCentre : EventNotifier {
    override suspend fun post(alert: TaskAlert) {
        runScript(scriptFor(alert))
        // A tone of the app's own is played by the app; Notification Centre only knows its own.
        if (alert.sound.isBundled) BundledTones.play(alert.sound)
    }

    /** The AppleScript this alert becomes. Separate from running it, so it can be read in a test. */
    internal fun scriptFor(alert: TaskAlert): String = buildString {
        append("display notification ${alert.body.quoted()} with title ${alert.title.quoted()}")
        // Notification Centre is silent unless a sound is named, so "default" has to name one.
        macSoundName(alert.sound)?.let { append(" sound name ${it.quoted()}") }
    }

    private suspend fun runScript(script: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    /** An AppleScript literal: the text is the user's, and a stray quote would end the statement. */
    private fun String.quoted(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * One of `/System/Library/Sounds`, or `null` for a sound macOS does not keep: silence, and the
 * tones the app brought with it.
 */
internal fun macSoundName(sound: NotificationSound): String? = when (sound) {
    NotificationSound.Default -> "Ping"
    NotificationSound.Alarm -> "Sosumi"
    NotificationSound.Silent, NotificationSound.Chime, NotificationSound.Bell -> null
}

/**
 * The tray balloon, where the desktop has a tray at all.
 *
 * The balloon has no say in what it sounds like, so anything the user asked for beyond the
 * platform's own noise is played here. Silence cannot be asked for: on Windows the balloon makes
 * the system's notification sound and AWT offers no way to stop it, so Silent is still heard and a
 * bundled tone is heard over the top of it.
 */
internal object TrayBalloon : EventNotifier {
    override suspend fun post(alert: TaskAlert) {
        // Both or neither: a desktop with no tray shows nothing, and a tone on its own is a noise
        // the user cannot account for. The engine has written the alert down as delivered by now,
        // so a desktop that cannot show it leaves a trace of it instead.
        val tray = zhedulerTrayIcon ?: return println("Zheduler: ${alert.title} - ${alert.body}")
        tray.displayMessage(alert.title, alert.body, TrayIcon.MessageType.INFO)
        if (alert.sound.isBundled) BundledTones.play(alert.sound)
    }
}

/** Plays the tones that travel with the app, straight through the Java sound system. */
internal object BundledTones {
    suspend fun play(sound: NotificationSound) {
        val bytes = readBundledTone(sound) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                // From memory rather than a stream off disk: reading the format needs mark and
                // reset, which a byte array gives for nothing.
                AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { audio ->
                    val clip = AudioSystem.getClip()
                    // A clip plays on its own thread, so it is closed when it reports having
                    // stopped rather than here, where closing would cut the tone off. Listening
                    // starts before the first note: a tone short enough to finish first would stop
                    // with nobody listening, and its line would never be given back.
                    clip.addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP) runCatching { clip.close() }
                    }
                    runCatching {
                        clip.open(audio)
                        clip.start()
                    }.onFailure { clip.close() }
                }
            }
        }
    }
}

/**
 * One icon for the lifetime of the process.
 *
 * Adding and removing one per alert makes some desktops flash an empty slot, and on others the
 * balloon is dismissed along with the icon that raised it.
 */
private val zhedulerTrayIcon: TrayIcon? by lazy {
    if (!SystemTray.isSupported()) return@lazy null
    runCatching {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                color = Color(0x3D, 0x5A, 0xFE)
                fillOval(0, 0, 16, 16)
                dispose()
            }
        }
        TrayIcon(image, "Zheduler")
            .apply { isImageAutoSize = true }
            .also { SystemTray.getSystemTray().add(it) }
    }.getOrNull()
}
