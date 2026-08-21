package com.zhelenskiy.zheduler.zheduler.events

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

/**
 * The sounds the user brought, kept as the app's own copies.
 *
 * A file is copied rather than remembered: the one that was chosen sits in a downloads folder, on
 * a memory card, or behind a permission that lasts as long as the picker did, and a reminder set
 * in January should still make a noise in June. The copy is what every later playback reads.
 */
expect object SoundLibrary {
    /** Puts [bytes] under [id] and returns whether it got there. */
    suspend fun store(id: String, bytes: ByteArray): Boolean

    /** The bytes of a stored sound, or `null` if the copy is gone. */
    suspend fun read(id: String): ByteArray?

    /** Whether the copy is still there, without reading it through. */
    suspend fun has(id: String): Boolean

    /** Removes a stored sound, if it is still there. */
    suspend fun remove(id: String)
}

/**
 * The choice as it can actually be played.
 *
 * A sound whose copy has gone — deleted from the library, or lost with the app's storage — leaves
 * the choice standing on what it was made alongside. Silence is never what a missing file should
 * turn into: a reminder nobody hears is worse than one that makes the wrong noise.
 */
suspend fun ChosenSound.playable(): ChosenSound {
    val chosen = custom ?: return this
    return if (SoundLibrary.has(chosen.id)) this else ChosenSound(builtin)
}

/**
 * Copies [file] into the library and returns it as something that can be chosen.
 *
 * The name kept is the one the file had; the name it is stored under is not, because two files
 * called `alarm.wav` are two sounds. The extension is carried across because iOS decides what it
 * can play by looking at it.
 */
suspend fun addToSoundLibrary(file: PlatformFile): CustomSound? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    val label = runCatching { file.name }.getOrNull().orEmpty().ifBlank { "Sound" }
    val id = newSoundId(label)
    return if (SoundLibrary.store(id, bytes)) CustomSound(id, label) else null
}

/**
 * A name no other sound has, ending in the same extension as what it was made from.
 *
 * Not the file's own name: the library is one flat directory, and a second `alarm.wav` would
 * quietly replace the first — leaving one reminder playing another's sound.
 */
internal fun newSoundId(label: String, unique: String = randomSoundId()): String {
    val extension = label.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() && it.length <= 5 }
        ?.lowercase()
        // iOS decides what it can play by the name alone and knows only the longer spelling, so
        // the shorter one is settled here rather than left to be refused at the far end.
        ?.let { if (it == "aif") "aiff" else it }
    return if (extension == null) unique else "$unique.$extension"
}

/** Distinct per call, and made of nothing a file system objects to. */
private fun randomSoundId(): String =
    (1..16).map { ALPHABET.random() }.joinToString("")

private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/**
 * What a file has to be called to be worth offering on this platform.
 *
 * Not a matter of taste: iOS plays a notification's sound itself and takes only the uncompressed
 * families, so offering an MP3 there would mean a preview that plays and a notification that does
 * not. Everywhere else the app plays the file itself and can take whatever the platform decodes.
 */
expect val soundExtensions: List<String>
