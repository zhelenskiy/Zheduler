package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

actual fun createLocationSource(): LocationSource = WebLocationSource

/**
 * The browser's own geolocation, which is as good as the device it runs on.
 *
 * A tab is not a service: nothing here runs once it is closed, so a crossing that happened while
 * the browser was shut is noticed the next time the app is opened rather than as it happens — the
 * same limit the web build already has on reminders.
 *
 * The answer comes back through a callback, and it is carried across as a string rather than as a
 * `Promise`. Both browser targets share this file, and the promise types they each speak are
 * different enough that one piece of code cannot await both; a string and a poll is the one shape
 * that compiles identically for Kotlin/JS and Kotlin/Wasm.
 */
private object WebLocationSource : LocationSource {

    override suspend fun currentFix(): GeoFix? {
        val token = beginLocationRequest()
        if (token.isEmpty()) return null
        try {
            var waited = 0.seconds
            while (waited < FIX_TIMEOUT) {
                readLocationResult(token).takeIf { it.isNotEmpty() }?.let { return parseFix(it) }
                delay(POLL_INTERVAL)
                waited += POLL_INTERVAL
            }
            return null
        } finally {
            forgetLocationRequest(token)
        }
    }

    /** `ok:<lat>:<lon>:<accuracy?>`, or anything else for a refusal. */
    private fun parseFix(result: String): GeoFix? {
        val parts = result.split(':')
        if (parts.firstOrNull() != "ok" || parts.size < 4) return null
        val latitude = parts[1].toDoubleOrNull() ?: return null
        val longitude = parts[2].toDoubleOrNull() ?: return null
        return GeoFix(
            point = GeoPoint(latitude = latitude, longitude = longitude),
            accuracyMeters = parts[3].toDoubleOrNull(),
        )
    }

    private val FIX_TIMEOUT = 20.seconds
    private val POLL_INTERVAL = 100.milliseconds
}

/**
 * Starts one reading and returns the name its answer will be left under, or `""` where the browser
 * has no geolocation at all.
 *
 * `maximumAge` lets a reading taken a minute ago stand, because sweeps come round more often than
 * a user crosses a boundary and each fresh reading is a wake-up of the device's radios.
 */
private fun beginLocationRequest(): String = js(
    """{
        if (typeof navigator === 'undefined' || !navigator.geolocation) return '';
        var store = globalThis.__zhedulerGeo || (globalThis.__zhedulerGeo = { next: 0 });
        var token = 'fix' + (store.next = store.next + 1);
        store[token] = '';
        navigator.geolocation.getCurrentPosition(
            function (position) {
                var accuracy = position.coords.accuracy;
                store[token] = 'ok:' + position.coords.latitude + ':' + position.coords.longitude +
                    ':' + (accuracy === null || accuracy === undefined ? '' : accuracy);
            },
            function () { store[token] = 'refused'; },
            { enableHighAccuracy: false, timeout: 15000, maximumAge: 60000 }
        );
        return token;
    }"""
)

private fun readLocationResult(token: String): String = js(
    """{
        var store = globalThis.__zhedulerGeo;
        if (!store) return '';
        return store[token] || '';
    }"""
)

private fun forgetLocationRequest(token: String): Unit = js(
    """{
        var store = globalThis.__zhedulerGeo;
        if (store) delete store[token];
    }"""
)

/** Whether this browser has geolocation to offer at all, whatever the user may later say to it. */
private fun browserHasGeolocation(): Boolean = js(
    """{ return typeof navigator !== 'undefined' && !!navigator.geolocation; }"""
)

@Composable
actual fun rememberLocationPermission(): LocationPermissionState = remember {
    object : LocationPermissionState {
        // A browser will not say in advance, and asking it to is a second permission of its own.
        // It prompts on the reading that needs the answer, so "the app may ask" is the whole of
        // what is known here — which is exactly what Granted means.
        override val status: LocationPermissionStatus =
            if (browserHasGeolocation()) LocationPermissionStatus.Granted
            else LocationPermissionStatus.Unavailable

        /** Brings the prompt forward, rather than leaving it to arrive on some later sweep. */
        override fun request() {
            beginLocationRequest()
        }

        // A closed tab runs nothing, whatever the user has permitted, so there is no standing
        // permission here to hold or to ask for.
        override val worksWhileAway: Boolean = false
        override val requestWhileAway: (() -> Unit)? = null
    }
}
