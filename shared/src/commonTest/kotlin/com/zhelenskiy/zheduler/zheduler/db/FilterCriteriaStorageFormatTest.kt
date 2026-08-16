@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Filter criteria are persisted per space and inside saved filters, so the JSON is a stored format:
 * a change to it silently drops whatever users already saved.
 */
class FilterCriteriaStorageFormatTest {

    private val populated = TaskFilterCriteria(
        searchQuery = "report",
        textSearchFields = persistentSetOf(TaskTextSearchField.Id, TaskTextSearchField.Title),
        statusFilters = persistentSetOf(TaskStatus.Open, TaskStatus.Declined("nope")),
        dueDateFilter = DueDateFilter.Custom,
        priorityFilter = PriorityFilter.Custom,
        estimatedTimeFilter = EstimatedTimeFilter.Custom,
        recurrenceFilter = RecurrenceFilter.FixedDaysOfWeek,
        notificationsFilter = NotificationsFilter.HasNotifications,
        autoUpdateStatusFilter = AutoUpdateStatusFilter.Auto,
        connectionTypeFilters = persistentSetOf(ConnectionTypeOption.DependsOn),
        selectedTags = persistentSetOf("home", "work"),
        tagMatchMode = TagMatchMode.Any,
        customPriorityMin = "10",
        customPriorityMax = "90",
        customDueDateBefore = Instant.fromEpochMilliseconds(1_700_000_000_000),
        customDueDateAfter = Instant.fromEpochMilliseconds(1_600_000_000_000),
        customEstimatedTimeMin = "30m",
        customEstimatedTimeMax = "2h",
        dependsOnTaskIds = "TEST-1",
        isDependencyOfTaskIds = "TEST-2",
        relatesToTaskIds = "TEST-3",
        subtaskOfTaskIds = "TEST-4",
        parentOfTaskIds = "TEST-5",
        blockedByTaskIds = "TEST-6",
        blockedByComment = "waiting",
        declinedReason = "obsolete",
    )

    @Test
    fun `criteria round-trip through their stored form`() {
        assertEquals(populated, populated.toJson().toTaskFilterCriteria())
        assertEquals(TaskFilterCriteria(), TaskFilterCriteria().toJson().toTaskFilterCriteria())
    }

    @Test
    fun `the stored form is unchanged`() {
        // Written out in full: this is the contract with every filter already on disk. Instants are
        // epoch milliseconds, and every field is present because defaults are encoded.
        val expected = """
            {"searchQuery":"report","textSearchFields":["Id","Title"],"statusFilters":[
            {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"},
            {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined","reason":"nope"}],
            "dueDateFilter":"Custom","priorityFilter":"Custom","estimatedTimeFilter":"Custom",
            "recurrenceFilter":"FixedDaysOfWeek","notificationsFilter":"HasNotifications",
            "autoUpdateStatusFilter":"Auto","connectionTypeFilters":["DependsOn"],
            "selectedTags":["home","work"],"tagMatchMode":"Any","customPriorityMin":"10",
            "customPriorityMax":"90","customDueDateBefore":1700000000000,
            "customDueDateAfter":1600000000000,"customEstimatedTimeMin":"30m",
            "customEstimatedTimeMax":"2h","dependsOnTaskIds":"TEST-1",
            "isDependencyOfTaskIds":"TEST-2","relatesToTaskIds":"TEST-3",
            "subtaskOfTaskIds":"TEST-4","parentOfTaskIds":"TEST-5","blockedByTaskIds":"TEST-6",
            "blockedByComment":"waiting","declinedReason":"obsolete"}
        """.trimIndent().replace("\n", "")

        assertEquals(expected, populated.toJson())
    }
}
