package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceDate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

actual fun createLocationSource(): LocationSource = IosLocationSource()

/**
 * One reading from Core Location per call.
 *
 * `requestLocation` rather than a running update stream: the engine asks when it sweeps and wants
 * an answer then, and a manager left delivering updates would keep the location hardware awake
 * between sweeps for readings nobody is listening to.
 *
 * Accuracy is asked for at a hundred metres. A fence is at least fifty across and usually a few
 * hundred, so the extra battery a GPS-grade fix costs buys nothing the boundary can tell apart.
 */
private class IosLocationSource : LocationSource {

    /**
     * The requests still waiting for an answer.
     *
     * `CLLocationManager.delegate` is a *weak* reference, so a delegate reachable only from there
     * can be collected the moment the block that made it returns — and a collected delegate is a
     * callback that never comes, which shows up as every single fix timing out. This is what holds
     * it, and answering is what lets it go.
     *
     * Touched only from the main queue, which is where the request is made, where Core Location
     * answers, and where a cancellation is bounced to. That is what makes a bare set safe here.
     */
    private val pending = mutableSetOf<OneFixDelegate>()

    override suspend fun currentFix(): GeoFix? = withTimeoutOrNull(FIX_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val delegate = OneFixDelegate { answered, fix ->
                pending -= answered
                if (continuation.isActive) continuation.resume(fix)
            }
            // A request that times out or is cancelled must not leave its delegate held for ever.
            continuation.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) { pending -= delegate }
            }
            // Core Location must be driven from a thread with a run loop, and the delegate's
            // callbacks arrive on the queue the manager was made on. The main queue is the only
            // one this app can promise either of.
            dispatch_async(dispatch_get_main_queue()) {
                pending += delegate
                val manager = CLLocationManager()
                delegate.attach(manager)
                if (!manager.authorizationStatus.isGranted()) {
                    delegate.giveUp()
                    return@dispatch_async
                }
                manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
                manager.requestLocation()
            }
        }
    }

    private companion object {
        val FIX_TIMEOUT = 20.seconds
    }
}

/**
 * Answers once and then stops, whichever way the request goes.
 *
 * A failure is as much of an answer as a location: a device with the hardware switched off calls
 * back with an error and never with a reading, and a caller left waiting for the latter would sit
 * out the whole timeout on every sweep.
 *
 * [onAnswer] is handed this delegate as well as the fix, so whoever is holding it can stop.
 */
@OptIn(ExperimentalForeignApi::class)
private class OneFixDelegate(
    private val onAnswer: (OneFixDelegate, GeoFix?) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    private var answered = false

    /** Held strongly, because the manager holds this one only weakly and the pair must outlive the call. */
    private var manager: CLLocationManager? = null

    fun attach(manager: CLLocationManager) {
        this.manager = manager
        manager.delegate = this
    }

    /** For the answer that needs no callback at all — no permission, so no point asking. */
    fun giveUp() = answer(null)

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        // The newest that is recent enough to mean anything. `requestLocation` very often answers
        // first with whatever was last cached, however old — and a stale reading here is worse
        // than none: it is written down as a measured answer, so a days-old fix from before a
        // journey says the device is still at home, and the first real fix then reads as having
        // left a house nobody was in. The Android side rejects stale fixes for the same reason.
        answer(
            didUpdateLocations.filterIsInstance<CLLocation>()
                .filter { NSDate().timeIntervalSinceDate(it.timestamp) <= MAX_FIX_AGE.inWholeSeconds }
                // Last, because Core Location delivers these in the order they were taken.
                .lastOrNull()
                ?.toFix()
        )
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        answer(null)
    }

    private fun answer(fix: GeoFix?) {
        if (answered) return
        answered = true
        manager?.delegate = null
        manager = null
        onAnswer(this, fix)
    }

    private fun CLLocation.toFix(): GeoFix {
        val point = coordinate.useContents { GeoPoint(latitude = latitude, longitude = longitude) }
        // Negative means the platform declines to say, which is not the same as a perfect reading.
        return GeoFix(point = point, accuracyMeters = horizontalAccuracy.takeIf { it >= 0 })
    }
}

/**
 * How old a reading may be and still be believed.
 *
 * Ten minutes, as on Android: long enough that a fix taken a moment ago still counts, short enough
 * that nobody goes anywhere and comes back inside it unnoticed. At file scope because a Kotlin
 * subclass of an Objective-C type may not carry a companion with anything in it.
 */
private val MAX_FIX_AGE = 10.minutes

private fun CLAuthorizationStatus.isGranted(): Boolean =
    this == kCLAuthorizationStatusAuthorizedWhenInUse || this == kCLAuthorizationStatusAuthorizedAlways

@Composable
actual fun rememberLocationPermission(): LocationPermissionState {
    val manager = remember { CLLocationManager() }
    var status by remember { mutableStateOf(manager.authorizationStatus) }

    // Remembered, not made inside the effect: the manager's delegate reference is weak, so a
    // watcher held by nothing else is collected and the screen stops noticing the user answering
    // the prompt — it would go on offering the button after permission had been granted.
    val watcher = remember { AuthorizationWatcher() }
    DisposableEffect(manager, watcher) {
        watcher.onChanged = { status = it }
        manager.delegate = watcher
        onDispose {
            watcher.onChanged = null
            manager.delegate = null
        }
    }

    return remember(status) {
        object : LocationPermissionState {
            override val status: LocationPermissionStatus
                get() = if (status.isGranted()) LocationPermissionStatus.Granted
                else LocationPermissionStatus.Denied

            override fun request() {
                manager.requestWhenInUseAuthorization()
            }

            override val worksWhileAway: Boolean
                get() = status == kCLAuthorizationStatusAuthorizedAlways

            // iOS will only offer the standing prompt once the app is already allowed while in
            // use, and it shows it at most once — after that the user has to go to Settings, which
            // is what the screen says when the button has been pressed and nothing changed.
            override val requestWhileAway: (() -> Unit)?
                get() = if (status == kCLAuthorizationStatusAuthorizedWhenInUse) {
                    { manager.requestAlwaysAuthorization() }
                } else {
                    null
                }
        }
    }
}

/** Reports the standing of the permission as the user answers the prompt. */
private class AuthorizationWatcher : NSObject(), CLLocationManagerDelegateProtocol {
    var onChanged: ((CLAuthorizationStatus) -> Unit)? = null

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onChanged?.invoke(manager.authorizationStatus)
    }
}

/** A granted permission is granted: nothing is put in front of the user by asking. */
actual val positioningPromptsOnUse: Boolean = false
