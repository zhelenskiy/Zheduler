@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * "Stops after N occurrences" has to mean N occurrences. The remaining count is decremented on
 * every fire, so comparing it against the running occurrence count mixes a countdown with a tally.
 */
class RecurrenceTerminationCountTest {

    private fun instant(year: Int, month: Int, day: Int, hour: Int = 0) =
        LocalDateTime(year, month, day, hour, 0).toInstant(TimeZone.UTC)

    private fun countOccurrences(afterOccurrences: Int): Int {
        val start = instant(2024, 1, 15, 9)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = start,
            ),
            statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
            resetToStatus = TaskStatus.Open,
            termination = RecurrenceTermination.afterOccurrences(afterOccurrences),
        )

        var rules = persistentListOf(rule to RecurrenceState())
        var now: Instant = start
        var fired = 0
        repeat(afterOccurrences + 5) {
            val result = RecurrenceService.processRecurrence(
                rules = rules,
                triggerEvent = RecurrenceTriggerEvent(TaskStatus.Done, now),
            ) ?: return fired
            rules = result.first
            fired++
            now += 1.days
        }
        return fired
    }

    @Test
    fun `a rule stopping after one occurrence fires once`() {
        assertEquals(1, countOccurrences(1))
    }

    @Test
    fun `a rule stopping after three occurrences fires three times`() {
        assertEquals(3, countOccurrences(3))
    }

    @Test
    fun `a rule stopping after five occurrences fires five times`() {
        assertEquals(5, countOccurrences(5))
    }
}
