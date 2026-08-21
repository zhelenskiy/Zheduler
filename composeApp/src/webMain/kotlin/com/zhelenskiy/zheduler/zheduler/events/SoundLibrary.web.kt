package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.storage.storeOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The browser's own storage, since a page has no directory to put a file in.
 *
 * Kept as text because that is what the storage holds; the cost is a third again in size, which
 * for a notification tone is a few tens of kilobytes. A sound too large for the quota simply fails
 * to be added, and the choice that would have used it never appears.
 */
@OptIn(ExperimentalEncodingApi::class)
actual object SoundLibrary {
    private fun store(id: String) = storeOf<String>(key = "sound_$id")

    actual suspend fun store(id: String, bytes: ByteArray): Boolean =
        runCatching { store(id).set(Base64.encode(bytes)) }.isSuccess

    actual suspend fun read(id: String): ByteArray? =
        runCatching { store(id).get()?.let(Base64::decode) }.getOrNull()

    // Read through, unlike everywhere else: the browser's storage holds text, not files, and
    // there is nothing to ask about it short of fetching it.
    actual suspend fun has(id: String): Boolean = read(id) != null

    actual suspend fun remove(id: String) {
        runCatching { store(id).delete() }
    }
}

/**
 * What a browser will play, where browsers disagree about nearly all of it. AIFF is left out
 * because only Safari reads it and a file the others cannot read previews as silence and stays
 * silent for every notification after; Ogg is kept on the same reckoning the other way round,
 * being the one Safari alone refuses.
 */
actual val soundExtensions: List<String> = listOf("wav", "mp3", "m4a", "ogg", "flac")
