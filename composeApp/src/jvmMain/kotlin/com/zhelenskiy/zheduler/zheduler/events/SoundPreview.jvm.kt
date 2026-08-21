package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Toolkit

/**
 * The system sound being played, so that the next choice replaces it rather than sounding over it.
 *
 * A tone of the app's own is left to finish: it runs about a second, and it is played through the
 * notifier's own clip rather than from here.
 */
private var playing: Process? = null
private val turn = Mutex()

actual suspend fun previewNotificationSound(sound: ChosenSound) = turn.withLock {
    val playable = sound.playable()
    playing?.destroy()
    playing = null
    if (playable.builtin == NotificationSound.Silent && playable.custom == null) return@withLock
    val own = ownToneBytes(playable)
    if (own != null) {
        OwnTones.play(own)
        return@withLock
    }
    val command = previewCommand(playable)
    if (command == null) {
        // The nearest this desktop has: AWT will not name what the balloon plays, and on a Linux
        // desktop with no bell configured this is nothing at all.
        runCatching { Toolkit.getDefaultToolkit().beep() }
        return@withLock
    }
    withContext(Dispatchers.IO) {
        // Started and left to it: waiting would hold the choice up for as long as the sound lasts.
        runCatching { playing = ProcessBuilder(command).redirectErrorStream(true).start() }
    }
    Unit
}

/**
 * How to play a sound the platform owns, or `null` where this desktop has no way to.
 *
 * macOS keeps the sounds Notification Centre uses as files anyone may play, so a preview is the
 * same sound the notification will make. Separate from playing it, so it can be read in a test.
 */
internal fun previewCommand(sound: ChosenSound): List<String>? {
    if (!isMacOs) return null
    val name = macSoundName(sound) ?: return null
    return listOf("afplay", "/System/Library/Sounds/$name.aiff")
}
