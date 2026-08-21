package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.events.NotificationSound
import com.zhelenskiy.zheduler.zheduler.geo.GeofenceDirection
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The names of these constants are on disk, and renaming one orphans what is already written.
 *
 * They reach storage two ways. Some are serialized by name inside a JSON column — the filters
 * inside a saved filter or a stored filter state, the fields and directions inside a view mode's
 * configuration. [ConnectionType] does not even go through serialization: its name is written
 * straight into the `type` column of every connection row and read back with `valueOf`. Either
 * way, a build that renames a constant cannot read what the previous one wrote, and for the
 * connection column it cannot read the row at all.
 *
 * Sister of [StoredVocabularyTest], which pins the class names of the polymorphic types. Those
 * could be pinned with `@SerialName`; most of these cannot, because nothing serializes them — so
 * this test is the whole of the protection. [NotificationSound] is the exception: it is serialized,
 * by name, and is pinned here beside the rest rather than somewhere of its own.
 *
 * Adding a constant is fine and passes. Renaming or removing one fails here, and the question that
 * raises is not how to update the list below — it is what happens to the rows already written.
 */
class StoredEnumNamesTest {

    private fun assertKeeps(names: Set<String>, expected: List<String>, what: String) {
        val missing = expected.filterNot { it in names }
        assertTrue(
            missing.isEmpty(),
            "$what no longer writes $missing — rows already written say those names",
        )
    }

    @Test
    fun `notification sounds keep the names written into every task's notifications`() {
        assertKeeps(
            NotificationSound.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Default", "System", "Silent", "Alarm", "Chime", "Bell"),
            "NotificationSound",
        )
    }

    @Test
    fun `geofence directions keep the names written into every location rule`() {
        assertKeeps(
            GeofenceDirection.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Entering", "Leaving", "EitherWay"),
            "GeofenceDirection",
        )
    }

    @Test
    fun `signal kinds and directions keep the names written into every location rule`() {
        assertKeeps(
            SignalKind.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Wifi", "Bluetooth"),
            "SignalKind",
        )
        assertKeeps(
            SignalDirection.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Appearing", "Disappearing", "EitherWay"),
            "SignalDirection",
        )
    }

    @Test
    fun `connection types keep the names written into every connection row`() {
        assertKeeps(
            ConnectionType.entries.mapTo(mutableSetOf()) { it.name },
            listOf("RelatesTo", "DependsOn", "IsDependencyOf", "SubtaskOf", "ParentOf"),
            "ConnectionType",
        )
    }

    @Test
    fun `view mode configuration keeps the names it is stored with`() {
        assertKeeps(
            GroupableField.entries.mapTo(mutableSetOf()) { it.name },
            listOf(
                "Status", "Priority", "DueDate", "EstimatedTime", "Tags",
                "HasConnections", "IsRecurring", "HasNotifications", "AutoUpdateStatus",
            ),
            "GroupableField",
        )
        assertKeeps(
            OrderableField.entries.mapTo(mutableSetOf()) { it.name },
            listOf(
                "Id", "Title", "Status", "Priority", "TotalPriority",
                "DueDate", "TotalDueDate", "EstimatedTime",
            ),
            "OrderableField",
        )
        assertKeeps(
            OrderDirection.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Ascending", "Descending"),
            "OrderDirection",
        )
        assertKeeps(
            NullPosition.entries.mapTo(mutableSetOf()) { it.name },
            listOf("First", "Last"),
            "NullPosition",
        )
    }

    @Test
    fun `saved filters keep the names they are stored with`() {
        assertKeeps(
            TaskTextSearchField.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Id", "Title", "Tags", "Description"),
            "TaskTextSearchField",
        )
        assertKeeps(
            DueDateFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "Overdue", "Today", "ThisWeek", "ThisMonth", "NoDueDate", "Custom"),
            "DueDateFilter",
        )
        assertKeeps(
            PriorityFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "High", "Medium", "Low", "NoPriority", "Custom"),
            "PriorityFilter",
        )
        assertKeeps(
            EstimatedTimeFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "NoEstimate", "Quick", "Short", "Medium", "Long", "VeryLong", "Custom"),
            "EstimatedTimeFilter",
        )
        assertKeeps(
            RecurrenceFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf(
                "Any", "NoRecurrence", "HasRecurrence", "AfterTimeout",
                "FixedDaysOfWeek", "FixedDayOfMonth", "NthDayOfWeek", "Yearly",
            ),
            "RecurrenceFilter",
        )
        assertKeeps(
            NotificationsFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "NoNotifications", "HasNotifications"),
            "NotificationsFilter",
        )
        assertKeeps(
            AutoUpdateStatusFilter.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "Auto", "Manual"),
            "AutoUpdateStatusFilter",
        )
        assertKeeps(
            ConnectionTypeOption.entries.mapTo(mutableSetOf()) { it.name },
            listOf("DependsOn", "IsDependencyOf", "RelatesTo", "SubtaskOf", "ParentOf", "NotSubtask"),
            "ConnectionTypeOption",
        )
        assertKeeps(
            TagMatchMode.entries.mapTo(mutableSetOf()) { it.name },
            listOf("Any", "All"),
            "TagMatchMode",
        )
    }
}
