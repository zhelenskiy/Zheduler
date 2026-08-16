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
 * Every migration the app ships, applied wherever a [ZhedulerDatabase] is opened.
 *
 * Databases in the field were created by SQLDelight at version 1 and carry no `room_master_table`;
 * Room reads their `user_version`, runs what is missing, and stamps its identity hash afterwards.
 */
internal fun RoomDatabase.Builder<ZhedulerDatabase>.withZhedulerMigrations(): RoomDatabase.Builder<ZhedulerDatabase> =
    addMigrations(DropUnusedTaskIndexes)
