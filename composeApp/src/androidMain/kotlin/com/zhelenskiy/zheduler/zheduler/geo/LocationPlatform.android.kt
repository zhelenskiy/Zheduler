package com.zhelenskiy.zheduler.zheduler.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

actual fun createLocationSource(): LocationSource = AndroidLocationSource(androidApplication())

/**
 * The platform's own positioning, through [LocationManager] rather than Play Services.
 *
 * Play Services' fused provider is better at this — it blends the sensors and it can hold a real
 * geofence that the system watches without the app running — but it is a Google dependency on a
 * build that also ships to browsers and desktops, and it is absent from a good many Android
 * devices. The engine sweeps on a schedule anyway, so what it needs from a platform is one fix
 * when it asks, which is the one thing every Android has.
 */
private class AndroidLocationSource(private val context: Context) : LocationSource {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun currentFix(): GeoFix? {
        if (!context.hasLocationPermission()) return null
        val manager = manager ?: return null
        return freshFix(manager) ?: lastKnownFix(manager)
    }

    /**
     * A reading taken now, on the platforms that will take one.
     *
     * Bounded by a timeout: `getCurrentLocation` waits for a fix indoors as long as it is left to,
     * and a sweep must not be one of the things waiting. A run that times out falls back on
     * whatever was last known, which for a boundary a few hundred metres wide is usually enough.
     */
    private suspend fun freshFix(manager: LocationManager): GeoFix? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = manager.bestEnabledProvider() ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location?.toFix())
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    /**
     * The most recent reading any enabled provider still holds, if it is recent enough to mean
     * anything.
     *
     * The age limit is the point. A cached fix has no expiry of its own — the passive provider will
     * happily hand back where the phone was on yesterday's drive — and a stale one is not merely
     * useless here but actively wrong: it is recorded as a *measured* answer, so a day-old fix ten
     * kilometres away says the user is outside the office, and the next real fix then reads as
     * having arrived at an office they never left. A rule fires and nobody moved.
     *
     * Measured against the clock that counts since boot rather than the wall clock, which the user
     * and the network can both move.
     */
    private fun lastKnownFix(manager: LocationManager): GeoFix? = try {
        val now = SystemClock.elapsedRealtimeNanos()
        manager.providers(enabledOnly = true)
            .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
            .filter { now - it.elapsedRealtimeNanos <= MAX_FIX_AGE.inWholeNanoseconds }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.toFix()
    } catch (_: SecurityException) {
        null
    }

    /** GPS where it is on, network otherwise: the finer of the two the device is willing to use. */
    private fun LocationManager.bestEnabledProvider(): String? {
        val enabled = providers(enabledOnly = true)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { it in enabled }
            ?: enabled.firstOrNull()
    }

    // Wrapped: a device with location switched off throws rather than answering with an empty list.
    private fun LocationManager.providers(enabledOnly: Boolean): List<String> =
        try {
            getProviders(enabledOnly)
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun Location.toFix() = GeoFix(
        point = GeoPoint(latitude = latitude, longitude = longitude),
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )

    private companion object {
        val FIX_TIMEOUT = 20.seconds

        /**
         * How old a cached reading may be and still be worth believing.
         *
         * Ten minutes: long enough that a fix taken while the app was last open still saves asking
         * the hardware again, short enough that nobody walks somewhere and back inside it without
         * a fresh reading arriving in between.
         */
        val MAX_FIX_AGE = 10.minutes
    }
}

/** Either of the two is enough: a fence of a few hundred metres does not need the finer one. */
internal fun Context.hasLocationPermission(): Boolean =
    LOCATION_PERMISSIONS.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Whether the app may be asked where the device is with nothing of it on the screen.
 *
 * Before Android 10 there was no such permission and the foreground one covered both, so a device
 * that has granted anything at all has granted this too.
 */
internal fun Context.hasBackgroundLocationPermission(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) hasLocationPermission()
    else checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
actual fun rememberLocationPermission(): LocationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasLocationPermission()) }
    var always by remember { mutableStateOf(context.hasBackgroundLocationPermission()) }

    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Read back rather than trusting the result map: a user who granted only the coarse
        // permission answers `false` for the fine one, and coarse is enough here.
        granted = context.hasLocationPermission()
        always = context.hasBackgroundLocationPermission()
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        always = context.hasBackgroundLocationPermission()
    }

    return remember(granted, always, foreground, background) {
        object : LocationPermissionState {
            override val status: LocationPermissionStatus
                get() = if (granted) LocationPermissionStatus.Granted else LocationPermissionStatus.Denied

            override fun request() {
                if (!granted) foreground.launch(LOCATION_PERMISSIONS)
            }

            override val worksWhileAway: Boolean get() = always

            // From Android 11 the standing permission may only be asked for once the ordinary one
            // is held, and asking for both together is refused outright — so this is offered only
            // after the first has been granted.
            override val requestWhileAway: (() -> Unit)?
                get() = when {
                    always || !granted -> null
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> null
                    else -> {
                        { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                    }
                }
        }
    }
}

/** A granted permission is granted: nothing is put in front of the user by asking. */
actual val positioningPromptsOnUse: Boolean = false
