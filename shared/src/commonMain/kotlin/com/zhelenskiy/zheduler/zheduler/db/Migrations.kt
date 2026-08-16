package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Drops three indexes inherited from the SQLDelight schema that no query can use.
 *
 * `idx_tasks_id_search` covers `tasks(id)`, which is the primary key: SQLite already maintains an
 * automatic unique index for it. The other two cover serialized JSON columns, and nothing seeks on
 * them — the filters either read inside the JSON with `json_extract` or compare the whole column to
 * `'[]'`, neither of which an index on the text can answer. All three were paid for on every insert
 * and update and returned nothing.
 */
internal val DropUnusedTaskIndexes = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
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
 * Every migration the app ships, applied wherever a [ZhedulerDatabase] is opened.
 *
 * Databases in the field were created by SQLDelight at version 1 and carry no `room_master_table`;
 * Room reads their `user_version`, runs what is missing, and stamps its identity hash afterwards.
 */
internal fun RoomDatabase.Builder<ZhedulerDatabase>.withZhedulerMigrations(): RoomDatabase.Builder<ZhedulerDatabase> =
    addMigrations(DropUnusedTaskIndexes, AddEstimatedTimeSeconds)
