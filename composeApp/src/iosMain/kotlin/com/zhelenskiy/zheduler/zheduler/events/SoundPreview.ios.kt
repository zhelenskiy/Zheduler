package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFAudio.AVAudioPlayer
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Held for as long as it plays: an [AVAudioPlayer] nobody keeps is collected mid-tone, and a
 * second preview replaces the first rather than sounding over it.
 */
private var player: AVAudioPlayer? = null

/** Reading a tone suspends, and a second choice arriving in that gap would slip past the stop. */
private val turn = Mutex()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun previewNotificationSound(sound: NotificationSound) = turn.withLock {
    runCatching { player?.stop() }
    player = null
    if (sound == NotificationSound.Silent) return@withLock
    val bytes = readBundledTone(sound)
    if (bytes == null) {
        // iOS lends out none of the sounds it announces notifications with, so a preview of one is
        // the system's own alert tone: the same idea, and since iOS 17 not the same sound.
        AudioServicesPlaySystemSound(SYSTEM_ALERT)
        return@withLock
    }
    if (bytes.isEmpty()) return@withLock
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    runCatching { player = AVAudioPlayer(data = data, error = null).apply { play() } }
    Unit
}

/** `kSystemSoundID_...` has no name for it; 1007 is the tone iOS announces things with. */
private const val SYSTEM_ALERT: UInt = 1007u
