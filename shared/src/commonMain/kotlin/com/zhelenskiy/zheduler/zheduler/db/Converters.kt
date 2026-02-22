@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmName
import kotlin.time.ExperimentalTime

/**
 * JSON serializer for database type conversions
 */
internal val dbJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Convert TaskStatus to JSON string for storage
 */
fun TaskStatus.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to TaskStatus
 */
fun String.toTaskStatus(): TaskStatus = dbJson.decodeFromString(this)

/**
 * Convert nullable TaskStatus to JSON string for storage
 */
fun TaskStatus?.toJsonOrNull(): String? = this?.let { dbJson.encodeToString(it) }

/**
 * Convert nullable JSON string to TaskStatus
 */
fun String?.toTaskStatusOrNull(): TaskStatus? = this?.let { dbJson.decodeFromString(it) }

/**
 * Convert AutomaticChangeReason to JSON string for storage
 */
fun AutomaticChangeReason?.toJsonOrNull(): String? = this?.let { dbJson.encodeToString(it) }

/**
 * Convert JSON string to AutomaticChangeReason
 */
fun String?.toAutomaticChangeReasonOrNull(): AutomaticChangeReason? = this?.let { dbJson.decodeFromString(it) }

/**
 * Convert RecurrencePeriod to JSON string for storage
 */
fun RecurrencePeriod?.toJsonOrNull(): String? = this?.let { dbJson.encodeToString(it) }

/**
 * Convert JSON string to RecurrencePeriod
 */
fun String?.toRecurrencePeriodOrNull(): RecurrencePeriod? = this?.let { dbJson.decodeFromString(it) }

/**
 * Convert Set<String> (tags) to JSON string for storage
 */
fun Set<String>.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to Set<String> (tags)
 */
fun String.toStringSet(): Set<String> = dbJson.decodeFromString(this)

/**
 * Convert List<TaskNotification> to JSON string for storage
 */
fun List<TaskNotification>.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to List<TaskNotification>
 */
fun String.toNotificationList(): List<TaskNotification> = dbJson.decodeFromString(this)

/**
 * Convert list of RecurrenceRule to JSON string for storage
 */
@JvmName("recurrenceRuleListToJson")
fun List<RecurrenceRule>.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to list of RecurrenceRule
 */
fun String.toRecurrenceRuleList(): List<RecurrenceRule> = dbJson.decodeFromString(this)

/**
 * Convert RecurrenceState to JSON string for storage
 */
fun RecurrenceState.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to RecurrenceState
 */
fun String.toRecurrenceState(): RecurrenceState = dbJson.decodeFromString(this)

/**
 * Convert TaskFilterCriteria to JSON string for storage
 */
fun TaskFilterCriteria.toJson(): String = dbJson.encodeToString(TaskFilterCriteriaSerializable.from(this))

/**
 * Convert JSON string to TaskFilterCriteria
 */
fun String.toTaskFilterCriteria(): TaskFilterCriteria = dbJson.decodeFromString<TaskFilterCriteriaSerializable>(this).toModel()

/**
 * Serializable version of TaskFilterCriteria
 */
@kotlinx.serialization.Serializable
data class TaskFilterCriteriaSerializable(
    val searchQuery: String = "",
    val textSearchFields: Set<TaskTextSearchField> = setOf(TaskTextSearchField.Id, TaskTextSearchField.Title),
    val statusFilters: Set<TaskStatus> = emptySet(),
    val dueDateFilter: DueDateFilter = DueDateFilter.Any,
    val priorityFilter: PriorityFilter = PriorityFilter.Any,
    val estimatedTimeFilter: EstimatedTimeFilter = EstimatedTimeFilter.Any,
    val recurrenceFilter: RecurrenceFilter = RecurrenceFilter.Any,
    val notificationsFilter: NotificationsFilter = NotificationsFilter.Any,
    val autoUpdateStatusFilter: AutoUpdateStatusFilter = AutoUpdateStatusFilter.Any,
    val connectionTypeFilters: Set<ConnectionTypeOption> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    val tagMatchMode: TagMatchMode = TagMatchMode.All,
    val customPriorityMin: String = "",
    val customPriorityMax: String = "",
    val customDueDateBefore: Long? = null,
    val customDueDateAfter: Long? = null,
    val customEstimatedTimeMin: String = "",
    val customEstimatedTimeMax: String = "",
    val dependsOnTaskIds: String = "",
    val isDependencyOfTaskIds: String = "",
    val relatesToTaskIds: String = "",
    val subtaskOfTaskIds: String = "",
    val parentOfTaskIds: String = "",
    val blockedByTaskIds: String = "",
    val blockedByComment: String = "",
    val declinedReason: String = ""
) {
    fun toModel() = TaskFilterCriteria(
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
        customDueDateBefore = customDueDateBefore?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
        customDueDateAfter = customDueDateAfter?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
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

    companion object {
        fun from(criteria: TaskFilterCriteria) = TaskFilterCriteriaSerializable(
            searchQuery = criteria.searchQuery,
            textSearchFields = criteria.textSearchFields,
            statusFilters = criteria.statusFilters,
            dueDateFilter = criteria.dueDateFilter,
            priorityFilter = criteria.priorityFilter,
            estimatedTimeFilter = criteria.estimatedTimeFilter,
            recurrenceFilter = criteria.recurrenceFilter,
            notificationsFilter = criteria.notificationsFilter,
            autoUpdateStatusFilter = criteria.autoUpdateStatusFilter,
            connectionTypeFilters = criteria.connectionTypeFilters,
            selectedTags = criteria.selectedTags,
            tagMatchMode = criteria.tagMatchMode,
            customPriorityMin = criteria.customPriorityMin,
            customPriorityMax = criteria.customPriorityMax,
            customDueDateBefore = criteria.customDueDateBefore?.toEpochMilliseconds(),
            customDueDateAfter = criteria.customDueDateAfter?.toEpochMilliseconds(),
            customEstimatedTimeMin = criteria.customEstimatedTimeMin,
            customEstimatedTimeMax = criteria.customEstimatedTimeMax,
            dependsOnTaskIds = criteria.dependsOnTaskIds,
            isDependencyOfTaskIds = criteria.isDependencyOfTaskIds,
            relatesToTaskIds = criteria.relatesToTaskIds,
            subtaskOfTaskIds = criteria.subtaskOfTaskIds,
            parentOfTaskIds = criteria.parentOfTaskIds,
            blockedByTaskIds = criteria.blockedByTaskIds,
            blockedByComment = criteria.blockedByComment,
            declinedReason = criteria.declinedReason
        )
    }
}
