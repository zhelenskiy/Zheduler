package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "spaces",
    indices = [Index(value = ["idPrefix"], unique = true)]
)
data class SpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val idPrefix: String
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("spaceId"),
        Index("isBlocked"),
        Index("isRecurring", "dueDate"),
        Index("id"),
        Index("title"),
        Index("spaceId", "status"),
        Index("spaceId", "priority"),
        Index("spaceId", "dueDate"),
        Index("spaceId", "estimatedTimeJson"),
        Index("spaceId", "autoUpdateStatusFromSubtasks"),
        Index("spaceId", "isRecurring"),
        Index("spaceId", "notificationsJson")
    ]
)
data class TaskEntity(
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
    val autoUpdateStatusFromSubtasks: Long,
    val isRecurring: Long,
    val isBlocked: Long
)

@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tag"), Index("taskId")]
)
data class TaskTagEntity(
    val taskId: String,
    val tag: String
)

@Entity(
    tableName = "task_connections",
    primaryKeys = ["sourceTaskId", "targetTaskId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceTaskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetTaskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sourceTaskId", "type"),
        Index("targetTaskId")
    ]
)
data class TaskConnectionEntity(
    val sourceTaskId: String,
    val targetTaskId: String,
    val type: String
)

@Entity(
    tableName = "status_changes",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId", "timestamp")]
)
data class StatusChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val timestamp: Long,
    val previousStatusJson: String?,
    val newStatusJson: String,
    val automaticChangeReasonJson: String?
)

@Entity(
    tableName = "space_next_ids",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SpaceNextIdEntity(
    @PrimaryKey val spaceId: String,
    val nextId: Long
)

@Entity(
    tableName = "tags",
    primaryKeys = ["spaceId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("spaceId"), Index("name")]
)
data class TagEntity(
    val spaceId: String,
    val name: String
)

@Entity(
    tableName = "filter_states",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FilterStateEntity(
    @PrimaryKey val spaceId: String,
    val criteriaJson: String
)

@Entity(
    tableName = "view_modes",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ViewModeEntity(
    @PrimaryKey val spaceId: String,
    val viewMode: String
)

@Entity(
    tableName = "filter_panel_states",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FilterPanelStateEntity(
    @PrimaryKey val spaceId: String,
    val isOpen: Long
)

@Entity(
    tableName = "custom_view_modes",
    primaryKeys = ["spaceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("spaceId")]
)
data class CustomViewModeEntity(
    val id: String,
    val spaceId: String,
    val name: String,
    val configJson: String
)

@Entity(
    tableName = "active_view_modes",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ActiveViewModeEntity(
    @PrimaryKey val spaceId: String,
    val viewModeId: String
)

@Entity(
    tableName = "saved_filters",
    primaryKeys = ["spaceId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("spaceId")]
)
data class SavedFilterEntity(
    val id: String,
    val spaceId: String,
    val name: String,
    val criteriaJson: String,
    val viewModeId: String?
)
