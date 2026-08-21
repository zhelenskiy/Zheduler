package com.zhelenskiy.zheduler.zheduler.events

import ca.gosyer.appdirs.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Beside the schedule and the settings, in the directory this desktop keeps application data in.
 *
 * Not a temporary directory: a reminder set today is meant to be heard in a month, and everything
 * in there can be cleared out by anything at any time.
 *
 * [DATA_DIRECTORY_PROPERTY] overrides it, which is for the tests: they add and delete real files,
 * and doing that where the developer's own copy of the app reads would leave their library holding
 * whatever a run that failed partway happened to stop in the middle of.
 */
private val soundsDirectory: File by lazy {
    val overridden = System.getProperty(DATA_DIRECTORY_PROPERTY)
    val parent = overridden ?: AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }.getUserDataDir()
    File(parent, "sounds").apply { mkdirs() }
}

/** Where the app keeps its data, when something other than this desktop decides. */
internal const val DATA_DIRECTORY_PROPERTY = "zheduler.data.dir"

internal fun customSoundFile(id: String): File = File(soundsDirectory, id)

actual object SoundLibrary {
    actual suspend fun store(id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            soundsDirectory.mkdirs()
            // Written beside and moved into place, so nothing reads half a copy. Where the move
            // has to be done by copying, the half-written one is cleared up rather than left.
            val staging = File(soundsDirectory, "$id.part")
            try {
                staging.writeBytes(bytes)
                staging.renameTo(customSoundFile(id)) ||
                    staging.copyTo(customSoundFile(id), overwrite = true).exists()
            } finally {
                staging.delete()
            }
        }.getOrDefault(false)
    }

    actual suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { customSoundFile(id).readBytes() }.getOrNull()
    }

    actual suspend fun has(id: String): Boolean = withContext(Dispatchers.IO) {
        customSoundFile(id).isFile
    }

    actual suspend fun remove(id: String) {
        withContext(Dispatchers.IO) { runCatching { customSoundFile(id).delete() } }
    }
}

/**
 * What `javax.sound.sampled` reads, and nothing else.
 *
 * The desktop plays a tone itself rather than handing it to anything, and the JDK ships decoders
 * for these three alone. Offering an MP3 would mean a file that copies, previews as silence, and
 * arrives with every notification as silence — with nothing anywhere saying why.
 */
actual val soundExtensions: List<String> = listOf("wav", "aiff", "aif", "aifc", "au")
