@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.runtime.*
import com.zhelenskiy.zheduler.zheduler.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Stable
class TaskFilterState {
    var searchQuery by mutableStateOf("")
    var textSearchFields by mutableStateOf(setOf(TaskTextSearchField.Id, TaskTextSearchField.Title))
    var statusFilters by mutableStateOf<Set<TaskStatus>>(emptySet())
    var dueDateFilter by mutableStateOf(DueDateFilter.Any)
    var priorityFilter by mutableStateOf(PriorityFilter.Any)
    var estimatedTimeFilter by mutableStateOf(EstimatedTimeFilter.Any)
    var recurrenceFilter by mutableStateOf(RecurrenceFilter.Any)
    var notificationsFilter by mutableStateOf(NotificationsFilter.Any)
    var autoUpdateStatusFilter by mutableStateOf(AutoUpdateStatusFilter.Any)
    var connectionTypeFilters by mutableStateOf(emptySet<ConnectionTypeOption>())
    var selectedTags by mutableStateOf(emptySet<String>())
    var tagMatchMode by mutableStateOf(TagMatchMode.All)

    var customPriorityMin by mutableStateOf("")
    var customPriorityMax by mutableStateOf("")
    var customDueDateBefore by mutableStateOf<Instant?>(null)  // Date picker
    var customDueDateAfter by mutableStateOf<Instant?>(null)   // Date picker
    var customEstimatedTimeMin by mutableStateOf("")  // Compact time format (e.g., "1h 30m")
    var customEstimatedTimeMax by mutableStateOf("")  // Compact time format (e.g., "2d")
    var dependsOnTaskIds by mutableStateOf("")  // comma-separated task IDs
    var isDependencyOfTaskIds by mutableStateOf("")  // comma-separated task IDs
    var relatesToTaskIds by mutableStateOf("")  // comma-separated task IDs
    var subtaskOfTaskIds by mutableStateOf("")  // comma-separated task IDs
    var parentOfTaskIds by mutableStateOf("")  // comma-separated task IDs
    var blockedByTaskIds by mutableStateOf("")  // for Blocked status filter - blocker task IDs
    var blockedByComment by mutableStateOf("")  // for Blocked status filter - search in comment
    var declinedReason by mutableStateOf("")  // for Declined status filter

    val hasActiveFilters: Boolean
        get() = toCriteria().hasActiveFilters

    /**
     * Convert UI state to [TaskFilterCriteria] for use with repository filtering
     */
    fun toCriteria(): TaskFilterCriteria = TaskFilterCriteria(
        searchQuery = searchQuery,
        textSearchFields = textSearchFields,
        statusFilters = statusFilters,
        dueDateFilter = dueDateFilter,
        priorityFilter = priorityFilter,
        estimatedTimeFilter = estimatedTimeFilter,
        recurrenceFilter = recurrenceFilter,
        notificationsFilter = notificationsFilter,
        autoUpdateStatusFilter = autoUpdateStatusFilter,
        connectionTypeFilters = connectionTypeFilters,
        selectedTags = selectedTags,
        tagMatchMode = tagMatchMode,
        customPriorityMin = customPriorityMin,
        customPriorityMax = customPriorityMax,
        customDueDateBefore = customDueDateBefore,
        customDueDateAfter = customDueDateAfter,
        customEstimatedTimeMin = customEstimatedTimeMin,
        customEstimatedTimeMax = customEstimatedTimeMax,
        dependsOnTaskIds = dependsOnTaskIds,
        isDependencyOfTaskIds = isDependencyOfTaskIds,
        relatesToTaskIds = relatesToTaskIds,
        subtaskOfTaskIds = subtaskOfTaskIds,
        parentOfTaskIds = parentOfTaskIds,
        blockedByTaskIds = blockedByTaskIds,
        blockedByComment = blockedByComment,
        declinedReason = declinedReason
    )

    /**
     * Load from [TaskFilterCriteria]
     */
    fun loadFromCriteria(criteria: TaskFilterCriteria) {
        searchQuery = criteria.searchQuery
        textSearchFields = criteria.textSearchFields
        statusFilters = criteria.statusFilters
        dueDateFilter = criteria.dueDateFilter
        priorityFilter = criteria.priorityFilter
        estimatedTimeFilter = criteria.estimatedTimeFilter
        recurrenceFilter = criteria.recurrenceFilter
        notificationsFilter = criteria.notificationsFilter
        autoUpdateStatusFilter = criteria.autoUpdateStatusFilter
        connectionTypeFilters = criteria.connectionTypeFilters
        selectedTags = criteria.selectedTags
        tagMatchMode = criteria.tagMatchMode
        customPriorityMin = criteria.customPriorityMin
        customPriorityMax = criteria.customPriorityMax
        customDueDateBefore = criteria.customDueDateBefore
        customDueDateAfter = criteria.customDueDateAfter
        customEstimatedTimeMin = criteria.customEstimatedTimeMin
        customEstimatedTimeMax = criteria.customEstimatedTimeMax
        dependsOnTaskIds = criteria.dependsOnTaskIds
        isDependencyOfTaskIds = criteria.isDependencyOfTaskIds
        relatesToTaskIds = criteria.relatesToTaskIds
        subtaskOfTaskIds = criteria.subtaskOfTaskIds
        parentOfTaskIds = criteria.parentOfTaskIds
        blockedByTaskIds = criteria.blockedByTaskIds
        blockedByComment = criteria.blockedByComment
        declinedReason = criteria.declinedReason
    }

    fun clearAll() {
        searchQuery = ""
        statusFilters = emptySet()
        dueDateFilter = DueDateFilter.Any
        priorityFilter = PriorityFilter.Any
        estimatedTimeFilter = EstimatedTimeFilter.Any
        recurrenceFilter = RecurrenceFilter.Any
        notificationsFilter = NotificationsFilter.Any
        autoUpdateStatusFilter = AutoUpdateStatusFilter.Any
        connectionTypeFilters = emptySet()
        selectedTags = emptySet()
        tagMatchMode = TagMatchMode.All
        customPriorityMin = ""
        customPriorityMax = ""
        customDueDateBefore = null
        customDueDateAfter = null
        customEstimatedTimeMin = ""
        customEstimatedTimeMax = ""
        dependsOnTaskIds = ""
        isDependencyOfTaskIds = ""
        relatesToTaskIds = ""
        subtaskOfTaskIds = ""
        parentOfTaskIds = ""
        blockedByTaskIds = ""
        blockedByComment = ""
        declinedReason = ""
    }
}

@Composable
fun rememberTaskFilterState(): TaskFilterState {
    return remember { TaskFilterState() }
}
