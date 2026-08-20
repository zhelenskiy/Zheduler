@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Which of the two passes to take when a wall-clock reading comes round twice in one day.
 */
enum class AmbiguousTimePolicy {
    Earlier,
    Later,
}

/**
 * Every instant at which [this] wall-clock reading happens in [tz], in order.
 *
 * Two on the day a zone puts its clocks back, none on the day it puts them forward, one otherwise.
 * A caller that must tell those apart — to fire a daily reminder once rather than twice, or to
 * decide what a reminder set inside a lost hour should do — asks here; [resolveIn] applies a policy
 * to the answer.
 */
fun LocalDateTime.occurrencesIn(tz: TimeZone): List<Instant> {
    val asUtc = toInstant(UtcOffset.ZERO)
    // Zone offsets stay within ±18h of UTC, so a day either side of the reading brackets every
    // offset it could be read in, and transitions are far enough apart that no third is in play.
    return listOf(tz.offsetAt(asUtc - 18.hours), tz.offsetAt(asUtc + 18.hours))
        .distinct()
        .map { toInstant(it) }
        .filter { it.toLocalDateTime(tz) == this }
        .distinct()
        .sorted()
}

/**
 * The instant this wall-clock reading names in [tz], moving on by the length of the jump if the
 * zone skipped it.
 *
 * With the default [ambiguous] this is what `toInstant(tz)` already does, spelled out — the value
 * of saying it here is that the choice is now visible and tested rather than inherited.
 */
fun LocalDateTime.resolveIn(
    tz: TimeZone,
    ambiguous: AmbiguousTimePolicy = AmbiguousTimePolicy.Earlier,
): Instant = resolveInOrNull(tz, ambiguous) ?: toInstant(tz)

/**
 * The instant this wall-clock reading names in [tz], or `null` on a day the zone skipped it.
 *
 * For callers to which a missing reading is an answer — a rule that should simply not fire that
 * day — rather than something to round past.
 */
fun LocalDateTime.resolveInOrNull(
    tz: TimeZone,
    ambiguous: AmbiguousTimePolicy = AmbiguousTimePolicy.Earlier,
): Instant? {
    val occurrences = occurrencesIn(tz)
    return when (ambiguous) {
        AmbiguousTimePolicy.Earlier -> occurrences.firstOrNull()
        AmbiguousTimePolicy.Later -> occurrences.lastOrNull()
    }
}
