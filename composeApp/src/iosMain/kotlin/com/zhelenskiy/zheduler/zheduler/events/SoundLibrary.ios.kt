package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * `Library/Sounds`, where the tones that ship with the app are put too.
 *
 * iOS plays a notification's sound itself and looks for it there by name, so a sound the user
 * added has to live in the same place as one of the app's own — there is nowhere else to put it
 * that a notification could name.
 */
actual object SoundLibrary {
    actual suspend fun store(id: String, bytes: ByteArray): Boolean {
        val staging = Path("$soundsDirectory/$id.part")
        return try {
            runCatching {
                val directory = Path(soundsDirectory)
                if (!SystemFileSystem.exists(directory)) SystemFileSystem.createDirectories(directory)
                SystemFileSystem.sink(staging).buffered().use { it.write(bytes) }
                SystemFileSystem.atomicMove(staging, Path("$soundsDirectory/$id"))
            }.isSuccess
        } finally {
            // Or a move that failed would leave it in the one directory iOS reads sounds from.
            runCatching { SystemFileSystem.delete(staging, mustExist = false) }
        }
    }

    actual suspend fun read(id: String): ByteArray? = runCatching {
        SystemFileSystem.source(Path("$soundsDirectory/$id")).buffered().use { it.readByteArray() }
    }.getOrNull()

    actual suspend fun has(id: String): Boolean =
        SystemFileSystem.exists(Path("$soundsDirectory/$id"))

    actual suspend fun remove(id: String) {
        runCatching { SystemFileSystem.delete(Path("$soundsDirectory/$id"), mustExist = false) }
    }
}

/** `UNNotificationSound` plays these and nothing else, and only thirty seconds of them. */
actual val soundExtensions: List<String> = listOf("wav", "aiff", "aif", "caf")
