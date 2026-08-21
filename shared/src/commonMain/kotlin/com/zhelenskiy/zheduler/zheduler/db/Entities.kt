package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/*
 * These entities reproduce, column for column, the schema SQLDelight used to create (see the
 * migration notes in the repository history). That is what lets Room adopt a database written by
 * an older build of the app: it finds no room_master_table, validates the live schema against
 * these declarations and, on a match, simply stamps its identity hash and carries on.
 *
 * Consequences worth knowing before editing:
 *  - index names are the original `idx_*` ones, and Room compares them verbatim (only names
 *    starting with `index_` are treated as interchangeable);
 *  - columns stay Long/Long? rather than Boolean/Int so the stored affinity is unchanged;
 *  - `spaces.idPrefix` was declared `UNIQUE` at the column level, which SQLite implements with an
 *    implicit autoindex that Room cannot see or express. Declaring an explicit unique index here
 *    would reject every existing database, so uniqueness is left to the repository, which already
 *    checks `prefixExists` before inserting a space.
 *
 * Three of the original indexes are gone: `idx_tasks_id_search` duplicated the primary key's
 * automatic index, and two more covered serialized JSON no query can seek on.
 *
 * An index only earns its place if the planner can seek on it, which needs the predicate to be a
 * plain conjunction: a range guarded by `:filterType = 0 OR ...` compiles to a scan, because one
 * plan has to serve every value the parameter might take. `idx_tasks_estimatedTimeSeconds` is
 * matched by the range variant of the filtered query for exactly that reason.
 */

@Entity(tableName = "spaces")
data class Spaces(
    @PrimaryKey val id: String,
    val name: String,
    val idPrefix: String,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_tasks_spaceId", value = ["spaceId"]),
        Index(name = "idx_tasks_isBlocked", value = ["isBlocked"]),
        Index(name = "idx_tasks_recurring_dueDate", value = ["isRecurring", "dueDate"]),
        Index(name = "idx_tasks_title_search", value = ["title"]),
        Index(name = "idx_tasks_status", value = ["spaceId", "status"]),
        Index(name = "idx_tasks_priority", value = ["spaceId", "priority"]),
        Index(name = "idx_tasks_dueDate", value = ["spaceId", "dueDate"]),
        Index(name = "idx_tasks_autoUpdateStatusFromSubtasks", value = ["spaceId", "autoUpdateStatusFromSubtasks"]),
        Index(name = "idx_tasks_isRecurring", value = ["spaceId", "isRecurring"]),
        Index(name = "idx_tasks_estimatedTimeSeconds", value = ["spaceId", "estimatedTimeSeconds"]),
    ],
)
data class Tasks(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val status: String,
    val dueDate: Long?,
    val priority: Long?,
    val estimatedTimeJson: String?,
    val tagsJson: String,
    val notificationsJson: String,
    val spaceId: String,
    val recurrenceRulesJson: String,
    @ColumnInfo(defaultValue = "0") val autoUpdateStatusFromSubtasks: Long,
    @ColumnInfo(defaultValue = "0") val isRecurring: Long,
    @ColumnInfo(defaultValue = "0") val isBlocked: Long,
    /**
     * [estimatedTimeJson] totalled into seconds, the way RecurrencePeriod.toApproximateSeconds
     * does it. Derived like [isRecurring] and [isBlocked]: the filters compare estimates as a
     * single number, and summing seven components out of JSON in every one of them was both
     * unreadable and impossible to index.
     */
    val estimatedTimeSeconds: Long?,
    /** What this task's deadline arriving sounds like; null where it sounds like the app does. */
    val dueSoundJson: String?,
)

/** Normalized counterpart of [Tasks.tagsJson], used for efficient tag lookups. */
@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = Tasks::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_task_tags_tag", value = ["tag"]),
        Index(name = "idx_task_tags_taskId", value = ["taskId"]),
    ],
)
data class TaskTags(
    val taskId: String,
    val tag: String,
)

@Entity(
    tableName = "task_connections",
    primaryKeys = ["sourceTaskId", "targetTaskId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = Tasks::class,
            parentColumns = ["id"],
            childColumns = ["sourceTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tasks::class,
            parentColumns = ["id"],
            childColumns = ["targetTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_connections_source_type", value = ["sourceTaskId", "type"]),
        Index(name = "idx_connections_target", value = ["targetTaskId"]),
    ],
)
data class TaskConnections(
    val sourceTaskId: String,
    val targetTaskId: String,
    val type: String,
)

@Entity(
    tableName = "status_changes",
    foreignKeys = [
        ForeignKey(
            entity = Tasks::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_status_changes_taskId_timestamp", value = ["taskId", "timestamp"]),
    ],
)
data class StatusChanges(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val taskId: String,
    val timestamp: Long,
    val previousStatusJson: String?,
    val newStatusJson: String,
    val automaticChangeReasonJson: String?,
)

@Entity(
    tableName = "space_next_ids",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpaceNextIds(
    @PrimaryKey val spaceId: String,
    val nextId: Long,
)

@Entity(
    tableName = "tags",
    primaryKeys = ["spaceId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_tags_spaceId", value = ["spaceId"]),
        Index(name = "idx_tags_name", value = ["name"]),
    ],
)
data class Tags(
    val spaceId: String,
    val name: String,
)

@Entity(
    tableName = "filter_states",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FilterStates(
    @PrimaryKey val spaceId: String,
    val criteriaJson: String,
)

@Entity(
    tableName = "view_modes",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ViewModes(
    @PrimaryKey val spaceId: String,
    val viewMode: String,
)

@Entity(
    tableName = "filter_panel_states",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FilterPanelStates(
    @PrimaryKey val spaceId: String,
    @ColumnInfo(defaultValue = "0") val isOpen: Long,
)

@Entity(
    tableName = "custom_view_modes",
    primaryKeys = ["spaceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_custom_view_modes_spaceId", value = ["spaceId"]),
    ],
)
data class CustomViewModes(
    val id: String,
    val spaceId: String,
    val name: String,
    val configJson: String,
)

@Entity(
    tableName = "active_view_modes",
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActiveViewModes(
    @PrimaryKey val spaceId: String,
    val viewModeId: String,
)

@Entity(
    tableName = "saved_filters",
    primaryKeys = ["spaceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = Spaces::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_saved_filters_spaceId", value = ["spaceId"]),
    ],
)
data class SavedFilters(
    val id: String,
    val spaceId: String,
    val name: String,
    val criteriaJson: String,
    val viewModeId: String?,
)

/**
 * The user's address book of places, to pick from when a rule is written.
 *
 * Not tied to a space, unlike everything above: where you live is where you live in every one of
 * them. That also means nothing cascades this table away, which is right — a rule keeps its own
 * copy of the area it watches, so an entry deleted here changes no rule, and a space deleted
 * elsewhere takes no place with it.
 *
 * Unindexed on purpose. The only query that is not "all of them" matches names with a leading
 * wildcard, which no index can seek on, and an address book is a few dozen rows.
 */
@Entity(tableName = "saved_locations")
data class SavedLocations(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val address: String,
)
