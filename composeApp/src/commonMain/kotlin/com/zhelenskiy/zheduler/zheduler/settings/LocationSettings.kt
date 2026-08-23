package com.zhelenskiy.zheduler.zheduler.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How often the device is asked where it is, while something is waiting on a place.
 *
 * The cost of a location rule is almost entirely this. Asked constantly, a phone that is going
 * nowhere spends its battery proving it; asked rarely, someone walking past the edge of a fence is
 * in and out again between two questions and the rule never fires. Neither is a setting a person
 * should have to think about, which is why [Automatic] is the default — but the right answer
 * depends on what the rules are *for*, and only the user knows that.
 *
 * The names are written to a settings file, and pinned by `LocationCheckRateTest`: renaming one
 * silently resets everyone who had chosen it back to the default.
 */
@Serializable
enum class LocationCheckRate(val label: String, val explanation: String, val fixed: Duration?) {
    @SerialName("Automatic")
    Automatic(
        label = "Automatic",
        explanation = "Often when you are near somewhere you watch, rarely when you are not",
        fixed = null,
    ),

    @SerialName("Often")
    Often("Every 15 seconds", "Catches a short walk past an edge. Costs the most battery", 15.seconds),

    @SerialName("Regular")
    Regular("Every minute", "Enough for arriving somewhere and staying", 1.minutes),

    @SerialName("Sparing")
    Sparing("Every 5 minutes", "For places you stay at, not ones you pass", 5.minutes),

    @SerialName("Rare")
    Rare("Every 15 minutes", "The cheapest. Expect to be told late", 15.minutes),
}

/**
 * How often to ask, given the rate the user chose and what the last look found.
 *
 * [Automatic] follows the device rather than the clock: the time to cover the distance to the
 * nearest edge at a brisk walk, bounded at both ends. Someone a kilometre away is asked every few
 * minutes; someone at the corner of the fence is asked as fast as the floor allows. [nearest] is
 * null where nothing was measured, which asks for the ordinary rate — not knowing where the device
 * is is not the same as knowing it is far away.
 */
fun LocationCheckRate.intervalFor(nearest: Double?): Duration {
    fixed?.let { return it }
    val metres = nearest?.takeIf { it.isFinite() && it >= 0 } ?: return AUTOMATIC_UNKNOWN
    val seconds = metres / WALKING_METRES_PER_SECOND
    return seconds.seconds.coerceIn(AUTOMATIC_FLOOR, AUTOMATIC_CEILING)
}

/** A brisk walk. Running or driving covers the distance sooner, and arrives to a late answer. */
private const val WALKING_METRES_PER_SECOND = 1.5

/** The most often [LocationCheckRate.Automatic] will ask, however close the edge. */
private val AUTOMATIC_FLOOR: Duration = 15.seconds

/** The least often it will ask, however far away everything is. */
private val AUTOMATIC_CEILING: Duration = 10.minutes

/** What it asks for when the last look measured nothing at all. */
private val AUTOMATIC_UNKNOWN: Duration = 1.minutes

/** Persisted app-wide, because it is about this device's battery rather than about one task. */
@Serializable
data class LocationSettings(
    val checkRate: LocationCheckRate = LocationCheckRate.Automatic,
)
