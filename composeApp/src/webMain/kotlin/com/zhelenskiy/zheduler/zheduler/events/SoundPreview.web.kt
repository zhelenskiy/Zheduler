package com.zhelenskiy.zheduler.zheduler.events

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Only the sounds the app has the bytes of: its own tones, and the ones the user added.
 *
 * The sound a browser notification makes is the browser's own and is not offered to the page, so
 * there is nothing to play for [NotificationSound.System] or [NotificationSound.Alarm] and those
 * stay choices made by name. Opening the dialog is a click, so the page has been interacted with
 * and a tone will not be refused for autoplay.
 */
actual suspend fun previewNotificationSound(sound: ChosenSound) {
    playTone(toneUri(sound).orEmpty())
}

/**
 * Where the page can fetch a sound from: the app's own tones are files it already serves, and a
 * sound the user added is held as bytes in the browser's storage, so it is handed over inline.
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun toneUri(sound: ChosenSound): String? {
    val custom = sound.custom ?: return bundledToneUri(sound.builtin)
    val bytes = ownToneBytes(sound) ?: return bundledToneUri(sound.builtin)
    return "data:${mimeOf(custom.id)};base64," + Base64.encode(bytes)
}

/**
 * What the bytes are, by the name they were stored under.
 *
 * A wildcard audio type is not a media type: a browser handed one has nothing to choose a decoder
 * by, and some refuse the data outright — which would make the sound the user went to the trouble
 * of adding the one that never plays. Anything unrecognised is offered as a WAV, as most tones are.
 */
private fun mimeOf(id: String): String = when (id.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "aiff" -> "audio/aiff"
    else -> "audio/wav"
}

/** Stops whatever the last choice started, then plays [uri] unless it is empty. */
private fun playTone(uri: String): Unit = js(
    """{
        try {
            if (globalThis.zhedulerPreview) globalThis.zhedulerPreview.pause();
            globalThis.zhedulerPreview = null;
            if (!uri) return;
            var audio = new Audio(uri);
            globalThis.zhedulerPreview = audio;
            var played = audio.play();
            if (played && played.catch) played.catch(function () {});
        } catch (e) {
        }
    }"""
)
