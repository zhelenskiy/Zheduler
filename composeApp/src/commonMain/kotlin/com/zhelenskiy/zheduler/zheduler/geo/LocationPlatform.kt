package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * How this platform answers "where is the device", if it answers at all.
 *
 * Made once and given to the engine, which asks it at most once a sweep and only when some rule is
 * watching a place. A platform with nothing to ask answers [NoLocationSource] and every rule that
 * waits on a place stays quiet there — which is the honest outcome, rather than one that fires
 * because the device is provably nowhere.
 */
expect fun createLocationSource(): LocationSource

/** What the user has said about the app reading where they are. */
enum class LocationPermissionStatus {
    /** The app may ask the platform where the device is. */
    Granted,

    /** The user has refused, or has not been asked yet and the platform will not say which. */
    Denied,

    /** Nothing to grant: this build has no way of finding out where the device is. */
    Unavailable,
}

/**
 * The standing of the location permission, and the way to ask for it.
 *
 * A composable rather than a suspend function because asking is the platform's own flow — an
 * Android permission dialog is tied to the activity that launched it, and the browser only prompts
 * from the call that needs the answer. [request] is what a button calls; [status] is what the
 * screen reads to decide whether to offer one.
 */
@Stable
interface LocationPermissionState {
    val status: LocationPermissionStatus

    /** Asks the user, where there is anything to ask. */
    fun request()

    /**
     * Whether the device can be asked where it is while the app is not on the screen.
     *
     * The distinction is the difference between a rule that fires when the user arrives somewhere
     * and one that fires the next time they open the app, and both phones make it: Android grants
     * "while using the app" and "all the time" separately, and so does iOS. False is not a
     * failure — the trigger still works, later — but it is worth saying on the screen.
     */
    val worksWhileAway: Boolean

    /**
     * Asks for the standing permission, or `null` where this platform has none to ask for.
     *
     * Null on the web, where a closed tab runs no code whatever the user permits.
     */
    val requestWhileAway: (() -> Unit)?
}

@Composable
expect fun rememberLocationPermission(): LocationPermissionState

/**
 * Whether asking where the device is can put a prompt in front of the user.
 *
 * Not the same question as the permission, and that is the point. A browser reports the permission
 * as *granted* meaning only "you may ask" — the prompt comes on the call itself, and a user who
 * dismisses rather than refuses is asked again on the next one. So a screen that quietly polls for
 * a fix, to show a distance nobody demanded, would sit there raising prompts.
 *
 * A phone that has already granted the permission prompts for nothing, which is why this is about
 * the platform rather than about the permission's state.
 */
expect val positioningPromptsOnUse: Boolean

/** For platforms where the question does not arise, and for previews. */
internal class FixedLocationPermission(
    override val status: LocationPermissionStatus,
    override val worksWhileAway: Boolean = false,
) : LocationPermissionState {
    override fun request() = Unit
    override val requestWhileAway: (() -> Unit)? = null
}
