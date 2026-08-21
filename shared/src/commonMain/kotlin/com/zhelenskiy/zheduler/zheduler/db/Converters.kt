@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmName
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
 * Convert JSON string to PersistentSet<String> (tags)
 */
fun String.toStringSet(): PersistentSet<String> = dbJson.decodeFromString<Set<String>>(this).toPersistentSet()

/**
 * A task's own choice for its deadline, or `null` where it has none of its own.
 *
 * Absent rather than written out when the task defers to the app: every task predating the column
 * reads back as deferring, which is what those tasks meant.
 */
fun ChosenSound.toJsonOrNull(): String? =
    if (isDeferred) null else dbJson.encodeToString(this)

/** The stored choice, or deferral — including where what was stored no longer decodes. */
fun String?.toChosenSound(): ChosenSound =
    this?.let { runCatching { dbJson.decodeFromString<ChosenSound>(it) }.getOrNull() }
        ?: ChosenSound.Deferred

/**
 * Convert List<TaskNotification> to JSON string for storage
 */
fun List<TaskNotification>.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to PersistentList<TaskNotification>
 */
fun String.toNotificationList(): PersistentList<TaskNotification> = dbJson.decodeFromString<List<TaskNotification>>(this).toPersistentList()

/**
 * Convert list of RecurrenceRule to JSON string for storage
 */
@JvmName("recurrenceRuleListToJson")
fun List<Pair<RecurrenceRule, RecurrenceState>>.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to list of RecurrenceRule
 */
fun String.toRecurrenceRuleList(): PersistentList<Pair<RecurrenceRule, RecurrenceState>> = dbJson.decodeFromString<List<Pair<RecurrenceRule, RecurrenceState>>>(this).toPersistentList()

/**
 * Convert TaskFilterCriteria to JSON string for storage
 */
fun TaskFilterCriteria.toJson(): String = dbJson.encodeToString(this)

/**
 * Convert JSON string to TaskFilterCriteria
 */
fun String.toTaskFilterCriteria(): TaskFilterCriteria = dbJson.decodeFromString(this)

/**
 * Serializable version of ViewMode config (without id, spaceId, name, isBuiltIn which are stored separately)
 */
@Serializable
data class ViewModeConfigSerializable(
    @Serializable(with = PersistentListSerializer::class)
    val groupingLevels: PersistentList<GroupingLevel> = persistentListOf(),
    @Serializable(with = PersistentListSerializer::class)
    val defaultOrderingRules: PersistentList<OrderingRule> = persistentListOf(
        OrderingRule(OrderableField.TotalDueDate, OrderDirection.Ascending, NullPosition.Last),
        OrderingRule(OrderableField.TotalPriority, OrderDirection.Descending, NullPosition.Last),
        OrderingRule(OrderableField.Id, OrderDirection.Ascending)
    )
)

/**
 * Convert ViewMode to config JSON string for storage
 */
fun ViewMode.toConfigJson(): String = dbJson.encodeToString(
    ViewModeConfigSerializable(
        groupingLevels = groupingLevels,
        defaultOrderingRules = defaultOrderingRules
    )
)

/**
 * Convert JSON string to ViewMode
 */
fun String.toViewMode(spaceId: String, id: String, name: String): ViewMode {
    val config = dbJson.decodeFromString<ViewModeConfigSerializable>(this)
    return ViewMode(
        id = id,
        name = name,
        spaceId = spaceId,
        isBuiltIn = false,
        groupingLevels = config.groupingLevels,
        defaultOrderingRules = config.defaultOrderingRules
    )
}
