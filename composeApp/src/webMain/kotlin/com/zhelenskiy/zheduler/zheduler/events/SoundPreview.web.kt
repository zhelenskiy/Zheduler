package com.zhelenskiy.zheduler.zheduler.events

/**
 * Only the tones the app brought with it.
 *
 * The sound a browser notification makes is the browser's own and is not offered to the page, so
 * there is nothing to play for [NotificationSound.Default] or [NotificationSound.Alarm] and those
 * stay choices made by name. Opening the menu is a click, so the page has been interacted with and
 * a tone will not be refused for autoplay.
 */
actual suspend fun previewNotificationSound(sound: NotificationSound) {
    playTone(bundledToneUri(sound).orEmpty())
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
