package com.zhelenskiy.zheduler.zheduler.events

import ca.gosyer.appdirs.AppDirs
import io.github.xxfast.kstore.file.storeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit

actual fun createScheduleStore(): ScheduleStore {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

/**
 * The desktop's own notifications, by whichever route this desktop actually has.
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
        val script = "display notification ${alert.body.quoted()} with title ${alert.title.quoted()}"
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

/** The tray balloon, where the desktop has a tray at all. */
internal object TrayBalloon : EventNotifier {
    override suspend fun post(alert: TaskAlert) {
        zhedulerTrayIcon?.displayMessage(alert.title, alert.body, TrayIcon.MessageType.INFO)
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
