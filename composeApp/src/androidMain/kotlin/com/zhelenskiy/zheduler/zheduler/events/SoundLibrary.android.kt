package com.zhelenskiy.zheduler.zheduler.events

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Inside the app's own storage, where nothing else can reach it and nothing else clears it. */
private fun soundsDirectory(context: Context): File =
    File(context.filesDir, "sounds").apply { mkdirs() }

internal fun customSoundFile(context: Context, id: String): File =
    File(soundsDirectory(context), id)

/**
 * A stored sound as something the system can play, or `null` if the copy is gone.
 *
 * A channel's sound is played by the system, not by this app, so a path into the app's private
 * storage is of no use: it has to be a content URI, and the process that plays it has to be given
 * permission to read it. The grant is made again on every post, since it does not outlive a
 * restart and costs nothing to repeat.
 */
internal fun customSoundUri(context: Context, id: String): Uri? {
    val file = customSoundFile(context, id)
    if (!file.isFile) return null
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.sounds", file)
    }.getOrNull() ?: return null
    for (player in PLAYERS) {
        // One at a time: not every device has every one of them, and a refusal from one is no
        // reason to give up the URI the others would have accepted.
        runCatching { context.grantUriPermission(player, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    return uri
}

/** Who ends up playing a notification: the shade on most devices, the media server underneath. */
private val PLAYERS = listOf("com.android.systemui", "android", "com.android.bluetooth")

actual object SoundLibrary {
    actual suspend fun store(id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val context = androidApplication()
        val staging = File(soundsDirectory(context), "$id.part")
        try {
            runCatching {
                staging.writeBytes(bytes)
                staging.renameTo(customSoundFile(context, id))
            }.getOrDefault(false)
        } finally {
            staging.delete()
        }
    }

    actual suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { customSoundFile(androidApplication(), id).readBytes() }.getOrNull()
    }

    actual suspend fun has(id: String): Boolean = withContext(Dispatchers.IO) {
        customSoundFile(androidApplication(), id).isFile
    }

    actual suspend fun remove(id: String) {
        withContext(Dispatchers.IO) {
            val context = androidApplication()
            runCatching { customSoundFile(context, id).delete() }
            // And the channel that played it, which would otherwise sit in the system's
            // notification settings for good, named after a file that is no longer there.
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.deleteNotificationChannel(customChannelId(id))
            }
        }
    }
}

/**
 * What Android's media framework decodes. AIFF is not among it, and a file it cannot read is one
 * that copies, previews as silence, and arrives with every notification as silence.
 */
actual val soundExtensions: List<String> = listOf("wav", "mp3", "m4a", "ogg", "opus", "flac")
