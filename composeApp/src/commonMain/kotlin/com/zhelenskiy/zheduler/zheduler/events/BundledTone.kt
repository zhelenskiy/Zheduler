package com.zhelenskiy.zheduler.zheduler.events

import org.jetbrains.compose.resources.ExperimentalResourceApi
import zheduler.composeapp.generated.resources.Res

/**
 * The bytes of a tone that ships with the app, or `null` for a sound the platform owns.
 *
 * One copy, read through Compose Resources, so desktop, iOS and the browser all play the same
 * file. Android is the exception and keeps its own copy under `res/raw`: it plays a notification
 * sound by handing the system a URI, and the system cannot read this module's assets.
 */
@OptIn(ExperimentalResourceApi::class)
suspend fun readBundledTone(sound: NotificationSound): ByteArray? {
    val name = sound.bundledName ?: return null
    return runCatching { Res.readBytes("files/sounds/$name.wav") }.getOrNull()
}

/**
 * The bytes of a sound the app plays for itself, or `null` where the platform plays it.
 *
 * A sound of the user's own first — that is what they chose — and what it was chosen alongside if
 * the copy has since gone missing, which for a file the user picked means the platform's own.
 */
suspend fun ownToneBytes(sound: ChosenSound): ByteArray? =
    sound.custom?.let { SoundLibrary.read(it.id) } ?: readBundledTone(sound.builtin)

/** Where the browser can fetch [sound] from, or `null` for a sound the platform owns. */
@OptIn(ExperimentalResourceApi::class)
fun bundledToneUri(sound: NotificationSound): String? {
    val name = sound.bundledName ?: return null
    return runCatching { Res.getUri("files/sounds/$name.wav") }.getOrNull()
}
