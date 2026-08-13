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
        Index(name = "idx_tasks_id_search", value = ["id"]),
        Index(name = "idx_tasks_title_search", value = ["title"]),
        Index(name = "idx_tasks_status", value = ["spaceId", "status"]),
        Index(name = "idx_tasks_priority", value = ["spaceId", "priority"]),
        Index(name = "idx_tasks_dueDate", value = ["spaceId", "dueDate"]),
        Index(name = "idx_tasks_estimatedTimeJson", value = ["spaceId", "estimatedTimeJson"]),
        Index(name = "idx_tasks_autoUpdateStatusFromSubtasks", value = ["spaceId", "autoUpdateStatusFromSubtasks"]),
        Index(name = "idx_tasks_isRecurring", value = ["spaceId", "isRecurring"]),
        Index(name = "idx_tasks_notificationsJson", value = ["spaceId", "notificationsJson"]),
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
