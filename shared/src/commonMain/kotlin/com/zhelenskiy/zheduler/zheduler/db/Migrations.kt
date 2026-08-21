package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Brings a database left by SQLDelight up to the shape Room expects, and drops three indexes
 * inherited from it that no query can use.
 *
 * **Filling in what is missing.** SQLDelight never numbered its schema in this project: it stayed
 * at version 1 while tables were added to it over several releases — tags on tasks, custom view
 * modes, saved filters. A database created by one of those earlier builds therefore still says
 * version 1 while lacking whatever came later, and Room, which checks the whole schema after
 * migrating, refused it: the migration rolled back, the version stayed put, and every launch after
 * that failed the same way, leaving the file intact but permanently unopenable. Each table is
 * created only if it is not already there, so for every other database this does nothing.
 *
 * The statements are Room's own, taken from the version-1 schema it exported; only `tasks` changed
 * shape after that, which the migrations below handle.
 *
 * **Dropping three indexes.** `idx_tasks_id_search` covers `tasks(id)`, which is the primary key:
 * SQLite already maintains an automatic unique index for it. The other two cover serialized JSON
 * columns, and nothing seeks on them — the filters either read inside the JSON with `json_extract`
 * or compare the whole column to `'[]'`, neither of which an index on the text can answer. All
 * three were paid for on every insert and update and returned nothing.
 */
internal val AdoptLegacySchema = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // language=SQL
        val missing = listOf(
            "CREATE TABLE IF NOT EXISTS `spaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `idPrefix` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `task_tags` (`taskId` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`taskId`, `tag`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_task_tags_tag` ON `task_tags` (`tag`)",
            "CREATE INDEX IF NOT EXISTS `idx_task_tags_taskId` ON `task_tags` (`taskId`)",
            "CREATE TABLE IF NOT EXISTS `task_connections` (`sourceTaskId` TEXT NOT NULL, `targetTaskId` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`sourceTaskId`, `targetTaskId`, `type`), FOREIGN KEY(`sourceTaskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`targetTaskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_connections_source_type` ON `task_connections` (`sourceTaskId`, `type`)",
            "CREATE INDEX IF NOT EXISTS `idx_connections_target` ON `task_connections` (`targetTaskId`)",
            "CREATE TABLE IF NOT EXISTS `status_changes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `previousStatusJson` TEXT, `newStatusJson` TEXT NOT NULL, `automaticChangeReasonJson` TEXT, FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_status_changes_taskId_timestamp` ON `status_changes` (`taskId`, `timestamp`)",
            "CREATE TABLE IF NOT EXISTS `space_next_ids` (`spaceId` TEXT NOT NULL, `nextId` INTEGER NOT NULL, PRIMARY KEY(`spaceId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `tags` (`spaceId` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`spaceId`, `name`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_tags_spaceId` ON `tags` (`spaceId`)",
            "CREATE INDEX IF NOT EXISTS `idx_tags_name` ON `tags` (`name`)",
            "CREATE TABLE IF NOT EXISTS `filter_states` (`spaceId` TEXT NOT NULL, `criteriaJson` TEXT NOT NULL, PRIMARY KEY(`spaceId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `view_modes` (`spaceId` TEXT NOT NULL, `viewMode` TEXT NOT NULL, PRIMARY KEY(`spaceId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `filter_panel_states` (`spaceId` TEXT NOT NULL, `isOpen` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`spaceId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `custom_view_modes` (`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `configJson` TEXT NOT NULL, PRIMARY KEY(`spaceId`, `id`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_custom_view_modes_spaceId` ON `custom_view_modes` (`spaceId`)",
            "CREATE TABLE IF NOT EXISTS `active_view_modes` (`spaceId` TEXT NOT NULL, `viewModeId` TEXT NOT NULL, PRIMARY KEY(`spaceId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `saved_filters` (`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `criteriaJson` TEXT NOT NULL, `viewModeId` TEXT, PRIMARY KEY(`spaceId`, `id`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_saved_filters_spaceId` ON `saved_filters` (`spaceId`)",
        )
        // And the indexes on `tasks`, which arrived just as piecemeal. Room checks a table's
        // indexes as closely as its columns, so filling in the tables alone still left the oldest
        // databases refused. The three the step below drops are deliberately absent.
        val missingTaskIndexes = listOf(
            "CREATE INDEX IF NOT EXISTS `idx_tasks_autoUpdateStatusFromSubtasks` ON `tasks` (`spaceId`, `autoUpdateStatusFromSubtasks`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_dueDate` ON `tasks` (`spaceId`, `dueDate`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_isBlocked` ON `tasks` (`isBlocked`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_isRecurring` ON `tasks` (`spaceId`, `isRecurring`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_priority` ON `tasks` (`spaceId`, `priority`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_recurring_dueDate` ON `tasks` (`isRecurring`, `dueDate`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_spaceId` ON `tasks` (`spaceId`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_status` ON `tasks` (`spaceId`, `status`)",
            "CREATE INDEX IF NOT EXISTS `idx_tasks_title_search` ON `tasks` (`title`)",
        )

        (missing + missingTaskIndexes).forEach { statement -> connection.execSQL(statement) }

        connection.execSQL("DROP INDEX IF EXISTS idx_tasks_id_search")
        connection.execSQL("DROP INDEX IF EXISTS idx_tasks_estimatedTimeJson")
        connection.execSQL("DROP INDEX IF EXISTS idx_tasks_notificationsJson")
    }
}

/**
 * Stores each task's estimate as a single number beside the JSON it is derived from.
 *
 * The filters compare estimates as one total, so every one of them summed the seven components out
 * of the JSON inline — the same eight-line expression repeated throughout the query. A stored
 * total, kept up to date on write like `isRecurring` and `isBlocked` are, makes those comparisons
 * one column reference.
 *
 * The backfill computes it the way `RecurrencePeriod.toApproximateSeconds` does, with 365-day years
 * and 30-day months, so existing rows agree with everything written afterwards.
 */
internal val AddEstimatedTimeSeconds = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE tasks ADD COLUMN estimatedTimeSeconds INTEGER")
        connection.execSQL(
            """
            UPDATE tasks SET estimatedTimeSeconds =
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(estimatedTimeJson, '$.seconds') AS INTEGER), 0)
            WHERE estimatedTimeJson IS NOT NULL
            """
        )
    }
}

/**
 * Indexes the estimate, now that a query exists which can seek on it.
 *
 * Room writes this index as `CREATE INDEX IF NOT EXISTS` over the same columns, so a database
 * migrated here validates against one created from the entities.
 */
internal val IndexEstimatedTimeSeconds = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_tasks_estimatedTimeSeconds` " +
                "ON `tasks` (`spaceId`, `estimatedTimeSeconds`)"
        )
    }
}

/**
 * Adds the column holding what a task's own deadline sounds like.
 *
 * Nullable and left null, which is how a task says it has no sound of its own and takes the app's
 * — so every task that existed before the column keeps sounding exactly as it did.
 */
internal val AddDueSound = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tasks` ADD COLUMN `dueSoundJson` TEXT")
    }
}

/**
 * Every migration the app ships, applied wherever a [ZhedulerDatabase] is opened.
 *
 * Databases in the field were created by SQLDelight at version 1 and carry no `room_master_table`;
 * Room reads their `user_version`, runs what is missing, and stamps its identity hash afterwards.
 * Version 1 is not one schema but several — see [AdoptLegacySchema].
 */
internal fun RoomDatabase.Builder<ZhedulerDatabase>.withZhedulerMigrations(): RoomDatabase.Builder<ZhedulerDatabase> =
    addMigrations(AdoptLegacySchema, AddEstimatedTimeSeconds, IndexEstimatedTimeSeconds, AddDueSound)
