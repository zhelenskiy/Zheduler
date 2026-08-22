package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.GeofenceDirection
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.LocationChange",
            nameOf(
                json.encodeToString<RecurrenceTrigger>(
                    RecurrenceTrigger.LocationChange(
                        areas = persistentSetOf(
                            GeoArea(name = "Home", point = GeoPoint(51.5, -0.12), radiusMeters = 200.0)
                        ),
                        direction = GeofenceDirection.Entering,
                    )
                )
            ),
        )
    }

    @Test
    fun `nearby signals keep the names they are stored under`() {
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.NearbyChange",
            nameOf(
                json.encodeToString<RecurrenceTrigger>(
                    RecurrenceTrigger.NearbyChange(
                        signals = persistentSetOf(NearbySignal.Wifi("Office")),
                        direction = SignalDirection.Appearing,
                    )
                )
            ),
        )
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Wifi",
            nameOf(json.encodeToString<NearbySignal>(NearbySignal.Wifi("Office"))),
        )
        assertEquals(
            "com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Bluetooth",
            nameOf(json.encodeToString<NearbySignal>(NearbySignal.Bluetooth("AA:BB", "Car"))),
        )
    }

    @Test
    fun `a rule written when wifi and bluetooth were one condition still watches both`() {
        // The two were split apart after this shipped. A rule saved by the old build carries the
        // combined field, and dropping it would not merely stop that rule working — it would
        // decode it with no condition at all, which is a rule that fires anywhere.
        val stored = """
            {
              "timeRecurrenceTrigger": null,
              "statusChangeTrigger": null,
              "resetToStatus": {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"},
              "nearbyTrigger": {
                "signals": [
                  {"type":"com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Wifi","ssid":"Office"},
                  {"type":"com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Bluetooth","address":"AA:BB:CC:DD:EE:FF","name":"Car"}
                ],
                "direction": "Appearing"
              }
            }
        """.trimIndent()

        val rule = json.decodeFromString<RecurrenceRule>(stored)

        assertEquals(null, rule.wifiTrigger)
        assertEquals(null, rule.bluetoothTrigger)
        assertNotNull(rule.nearbyTrigger)
        assertEquals(
            1,
            rule.presenceTriggers.size,
            "the old condition is still one of the rule's conditions, so the rule still fires",
        )
        assertEquals(2, assertNotNull(rule.nearbyTrigger).signals.size)
    }

    @Test
    fun `a rule saved now writes the two apart and nothing under the old name`() {
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = null,
            statusChangeTrigger = null,
            resetToStatus = TaskStatus.Open,
            wifiTrigger = RecurrenceTrigger.NearbyChange(
                persistentSetOf(NearbySignal.Wifi("Office")),
                SignalDirection.Appearing,
            ),
            bluetoothTrigger = RecurrenceTrigger.NearbyChange(
                persistentSetOf(NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car")),
                SignalDirection.Disappearing,
            ),
        )

        val restored = json.decodeFromString<RecurrenceRule>(json.encodeToString(rule))

        assertEquals(rule, restored)
        assertEquals(null, restored.nearbyTrigger)
        assertEquals(
            2,
            restored.presenceTriggers.size,
            "each kind is a condition of its own, so each can want its own direction",
        )
        assertEquals(
            SignalDirection.Disappearing,
            assertNotNull(restored.bluetoothTrigger).direction,
            "which is the whole point of splitting them",
        )
    }

    @Test
    fun `the two new conditions are stored under the names they were given`() {
        // Round-tripping a rule through this build proves nothing about a rule saved by it and
        // read by the next one: rename either property and every stored rule decodes with that
        // condition missing, which is a rule that fires anywhere rather than one that stops.
        val stored = """
            {
              "timeRecurrenceTrigger": null,
              "statusChangeTrigger": null,
              "resetToStatus": {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"},
              "wifiTrigger": {
                "signals": [{"type":"com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Wifi","ssid":"Office"}],
                "direction": "Appearing"
              },
              "bluetoothTrigger": {
                "signals": [{"type":"com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Bluetooth","address":"AA:BB:CC:DD:EE:FF","name":"Car"}],
                "direction": "Disappearing"
              }
            }
        """.trimIndent()

        val rule = json.decodeFromString<RecurrenceRule>(stored)

        assertEquals(
            listOf(NearbySignal.Wifi("Office")),
            assertNotNull(rule.wifiTrigger).signals.toList(),
        )
        assertEquals(
            SignalDirection.Disappearing,
            assertNotNull(rule.bluetoothTrigger).direction,
        )
    }

    @Test
    fun `a rule written before there were signals still reads back`() {
        // Both fields live in the same JSON column and neither was there when most rules were
        // written, so both have to decode from their absence.
        val stored = """
            {
              "timeRecurrenceTrigger": null,
              "statusChangeTrigger": null,
              "resetToStatus": {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"},
              "locationTrigger": {
                "areas":[{"name":"Home","point":{"latitude":51.5,"longitude":-0.12},"radiusMeters":200.0}],
                "direction":"Entering"
              }
            }
        """.trimIndent()

        val rule = json.decodeFromString<RecurrenceRule>(stored)

        assertEquals(null, rule.nearbyTrigger)
        assertNotNull(rule.locationTrigger)
        assertEquals(1, rule.presenceTriggers.size)
    }

    @Test
    fun `a rule that watches a place and a network survives being written and read back`() {
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = null,
            statusChangeTrigger = null,
            resetToStatus = TaskStatus.Open,
            locationTrigger = RecurrenceTrigger.LocationChange(
                areas = persistentSetOf(
                    GeoArea(name = "Office", point = GeoPoint(51.5, -0.12), radiusMeters = 1.0)
                ),
                direction = GeofenceDirection.Entering,
            ),
            nearbyTrigger = RecurrenceTrigger.NearbyChange(
                signals = persistentSetOf(
                    NearbySignal.Wifi("Office"),
                    NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Desk phone"),
                ),
                direction = SignalDirection.Appearing,
            ),
        )

        val restored = json.decodeFromString<RecurrenceRule>(json.encodeToString(rule))

        assertEquals(rule, restored)
        assertEquals(2, restored.presenceTriggers.size)
        assertEquals(
            listOf("Office", "Desk phone"),
            assertNotNull(restored.nearbyTrigger).signals.map { it.label },
            "the order signals were chosen in is the order they are shown in",
        )
    }

    @Test
    fun `a rule written before there were places still reads back`() {
        // The rules live in a JSON column, so every rule anyone has ever saved lacks this field.
        // A default is what lets it decode; without one the task will not read at all, and the
        // space it belongs to goes with it.
        val stored = """
            {
              "timeRecurrenceTrigger": null,
              "statusChangeTrigger": {
                "requiredStatuses": [{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Done"}]
              },
              "resetToStatus": {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"}
            }
        """.trimIndent()

        val rule = json.decodeFromString<RecurrenceRule>(stored)

        assertEquals(null, rule.locationTrigger)
        assertEquals(TaskStatus.Open, rule.resetToStatus)
    }

    @Test
    fun `a place survives being written and read back whole`() {
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = null,
            statusChangeTrigger = null,
            resetToStatus = TaskStatus.Open,
            locationTrigger = RecurrenceTrigger.LocationChange(
                areas = persistentSetOf(
                    GeoArea(name = "Home", point = GeoPoint(51.5, -0.12), radiusMeters = 200.0),
                    GeoArea(name = "Work", point = GeoPoint(51.6, -0.10), radiusMeters = 500.0),
                ),
                direction = GeofenceDirection.Leaving,
            ),
        )

        val restored = json.decodeFromString<RecurrenceRule>(json.encodeToString(rule))

        assertEquals(rule, restored)
        assertEquals(
            listOf("Home", "Work"),
            assertNotNull(restored.locationTrigger).areas.map { it.name },
            "the order places were chosen in is the order they are shown in",
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
