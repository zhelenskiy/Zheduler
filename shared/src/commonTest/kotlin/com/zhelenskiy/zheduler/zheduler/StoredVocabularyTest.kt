package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The names these types are written under are part of the database, not an implementation detail.
 *
 * They sit in every task row, every timeline entry, every saved filter and every export file, and
 * the SQL that groups tasks by status matches them as text (see the status clauses in ZhedulerDao).
 * Left to default, each name is the class's own fully-qualified name — so renaming or moving a
 * class would leave every stored row naming something that no longer exists: decoding would fail,
 * and the grouping would quietly stop matching without failing at all.
 *
 * This test is here to fail loudly if one of those names ever changes. If it does, the question is
 * not how to update the expectation — it is what happens to the rows already written.
 */
@OptIn(ExperimentalTime::class)
class StoredVocabularyTest {

    private val json = Json

    private fun nameOf(encoded: String): String =
        Regex("\"type\":\"([^\"]+)\"").find(encoded)?.groupValues?.get(1) ?: "«no discriminator in $encoded»"

    @Test
    fun `task statuses keep the names they are stored under`() {
        val expected = mapOf(
            TaskStatus.Open to "com.zhelenskiy.zheduler.zheduler.TaskStatus.Open",
            TaskStatus.InProgress to "com.zhelenskiy.zheduler.zheduler.TaskStatus.InProgress",
            TaskStatus.Done to "com.zhelenskiy.zheduler.zheduler.TaskStatus.Done",
            TaskStatus.Blocked(persistentSetOf("TEST-1")) to "com.zhelenskiy.zheduler.zheduler.TaskStatus.Blocked",
            TaskStatus.Declined("no") to "com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined",
        )

        for ((status, name) in expected) {
            assertEquals(name, nameOf(json.encodeToString<TaskStatus>(status)))
        }
    }

    @Test
    fun `automatic change reasons keep the names they are stored under`() {
        val expected = mapOf<AutomaticChangeReason, String>(
            AutomaticChangeReason.Unblocked to "com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.Unblocked",
            AutomaticChangeReason.Recurrence to "com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.Recurrence",
            AutomaticChangeReason.UpdatedFromSubtasks(persistentListOf("TEST-1")) to
                    "com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.UpdatedFromSubtasks",
        )

        for ((reason, name) in expected) {
            assertEquals(name, nameOf(json.encodeToString<AutomaticChangeReason>(reason)))
        }
    }

    @Test
    fun `recurrence triggers and their parts keep the names they are stored under`() {
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.AfterTimeout",
            nameOf(
                json.encodeToString<RecurrenceTrigger>(
                    RecurrenceTrigger.AfterTimeout(
                        period = RecurrencePeriod(days = 1),
                        firstOccurrence = Instant.fromEpochMilliseconds(0),
                    )
                )
            ),
        )
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.RecurrenceTimeZone.SystemDefault",
            nameOf(json.encodeToString<RecurrenceTimeZone>(RecurrenceTimeZone.SystemDefault)),
        )
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.RecurrenceTerminationCondition.AfterOccurrences",
            nameOf(
                json.encodeToString<RecurrenceTerminationCondition>(
                    RecurrenceTerminationCondition.AfterOccurrences(3)
                )
            ),
        )
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.FixedPointPattern.DayOfMonth",
            nameOf(json.encodeToString<FixedPointPattern>(FixedPointPattern.DayOfMonth(1, TimeOfDay(9, 0)))),
        )
    }

    @Test
    fun `a status written today is still the shape the grouping SQL looks for`() {
        // The clauses in ZhedulerDao match `{"type":"…TaskStatus.Open"` from the start of the
        // column, so the name has to be the first key as well as the right text.
        assertTrue(
            json.encodeToString<TaskStatus>(TaskStatus.Open)
                .startsWith("{\"type\":\"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open\""),
            "the grouping SQL will silently stop matching: ${json.encodeToString<TaskStatus>(TaskStatus.Open)}",
        )
    }
}
