package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import com.zhelenskiy.zheduler.zheduler.EstimatedTimeFilter
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import kotlinx.collections.immutable.persistentListOf
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Databases in the field were created by SQLDelight, which stamped `user_version = 1` and never
 * wrote a `room_master_table`. Room reads that version, runs the migrations that take it to the
 * current one, and validates the result against the schema generated from the entities. That only
 * holds while the entities plus the migrations reproduce the DDL below, so this test drives a real
 * SQLDelight-shaped database through Room and fails loudly the moment either drifts.
 *
 * [LEGACY_SQLDELIGHT_DDL] is the verbatim schema from the `ZhedulerDatabase.sq` this migration
 * replaced; treat it as a fixture of history and do not "fix" it to match new entities.
 */
class LegacySqlDelightSchemaCompatibilityTest {

    @Test
    fun `room opens a database created by sqldelight and keeps its rows`() {
        val dbFile = File(Files.createTempDirectory("legacy-sqldelight").toFile(), "zheduler.db")

        createLegacyDatabase(dbFile)

        val database = Room.databaseBuilder<ZhedulerDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .withZhedulerMigrations()
            .build()

        try {
            runBlocking {
                val repository = RoomTaskRepository(database)

                val spaces = repository.getAllSpaces()
                assertEquals(1, spaces.size, "the pre-existing space should survive the migration")
                assertEquals("Legacy", spaces.single().name)
                assertEquals("LEG", spaces.single().idPrefix)

                val task = repository.getTaskById("LEG-1")
                assertTrue(task != null, "the pre-existing task should be readable")
                assertEquals("Old task", task.title)
                assertEquals(setOf("legacy"), task.tags)
                assertEquals(1, repository.getStatusTimeline("LEG-1").size)

                assertEquals(RecurrencePeriod(hours = 2), task.estimatedTime)

                // The estimate is filtered on through the column the migration backfilled, so this
                // only finds the task if the backfill computed it from the legacy JSON.
                val longTasks = repository.getTasksForGroup(
                    spaceId = spaces.single().id,
                    filters = persistentListOf(),
                    orderingRules = persistentListOf(),
                    filterCriteria = TaskFilterCriteria(estimatedTimeFilter = EstimatedTimeFilter.Long),
                )
                assertEquals(listOf("LEG-1"), longTasks.map { it.task.id })

                // Writing still works against the adopted file.
                assertTrue(repository.addTask(spaces.single().id, "New task", "", task.status) != null)
                assertEquals(2, repository.getAllTasks(spaces.single().id).size)
            }
        } finally {
            database.close()
        }

        // Having validated the schema, Room stamps its identity hash into the adopted file.
        withConnection(dbFile) { connection ->
            val hasMasterTable = connection
                .prepare("SELECT count(*) FROM sqlite_master WHERE name = 'room_master_table'")
                .use { it.step(); it.getLong(0) }
            assertEquals(1L, hasMasterTable, "Room should have adopted the database")

            val version = connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
            assertEquals(3L, version, "the adopted database should have been migrated")

            // What the migration is for: the legacy file carried these, the current schema does not.
            listOf("idx_tasks_id_search", "idx_tasks_estimatedTimeJson", "idx_tasks_notificationsJson")
                .forEach { index ->
                    val present = connection
                        .prepare("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$index'")
                        .use { it.step(); it.getLong(0) }
                    assertEquals(0L, present, "$index should have been dropped")
                }

            // The indexes that do earn their keep are untouched.
            val kept = connection
                .prepare("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_tasks_spaceId'")
                .use { it.step(); it.getLong(0) }
            assertEquals(1L, kept, "idx_tasks_spaceId should still be there")
        }
    }

    private fun createLegacyDatabase(dbFile: File) = withConnection(dbFile) { connection ->
        LEGACY_SQLDELIGHT_DDL.forEach(connection::execSQL)
        // SQLDelight stamps the schema version, which is what sends Room down the "adopt an
        // existing database" path instead of the create path.
        connection.execSQL("PRAGMA user_version = 1")

        connection.execSQL("INSERT INTO spaces(id, name, idPrefix) VALUES ('space-0-LEG', 'Legacy', 'LEG')")
        connection.execSQL("INSERT INTO space_next_ids(spaceId, nextId) VALUES ('space-0-LEG', 2)")
        connection.execSQL(
            """
            INSERT INTO tasks(id, title, description, status, dueDate, priority, estimatedTimeJson,
                              tagsJson, notificationsJson, spaceId, recurrenceRulesJson,
                              autoUpdateStatusFromSubtasks, isRecurring, isBlocked)
            VALUES ('LEG-1', 'Old task', '', '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"}',
                    NULL, NULL, '{"years":0,"months":0,"weeks":0,"days":0,"hours":2,"minutes":0,"seconds":0}',
                    '["legacy"]', '[]', 'space-0-LEG', '[]', 0, 0, 0)
            """
        )
        connection.execSQL("INSERT INTO task_tags(taskId, tag) VALUES ('LEG-1', 'legacy')")
        connection.execSQL("INSERT INTO tags(spaceId, name) VALUES ('space-0-LEG', 'legacy')")
        connection.execSQL(
            """
            INSERT INTO status_changes(taskId, timestamp, previousStatusJson, newStatusJson, automaticChangeReasonJson)
            VALUES ('LEG-1', 1700000000000, NULL, '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"}', NULL)
            """
        )
    }

    private fun withConnection(dbFile: File, block: (SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }
}

/** The schema exactly as `ZhedulerDatabase.sq` created it, before the Room migration. */
private val LEGACY_SQLDELIGHT_DDL = listOf(
    """
    CREATE TABLE spaces (
        id TEXT NOT NULL PRIMARY KEY,
        name TEXT NOT NULL,
        idPrefix TEXT NOT NULL UNIQUE
    )
    """,
    """
    CREATE TABLE tasks (
        id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        description TEXT NOT NULL,
        status TEXT NOT NULL,
        dueDate INTEGER,
        priority INTEGER,
        estimatedTimeJson TEXT,
        tagsJson TEXT NOT NULL,
        notificationsJson TEXT NOT NULL,
        spaceId TEXT NOT NULL,
        recurrenceRulesJson TEXT NOT NULL,
        autoUpdateStatusFromSubtasks INTEGER NOT NULL DEFAULT 0,
        isRecurring INTEGER NOT NULL DEFAULT 0,
        isBlocked INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_tasks_spaceId ON tasks(spaceId)",
    "CREATE INDEX idx_tasks_isBlocked ON tasks(isBlocked)",
    "CREATE INDEX idx_tasks_recurring_dueDate ON tasks(isRecurring, dueDate)",
    "CREATE INDEX idx_tasks_id_search ON tasks(id)",
    "CREATE INDEX idx_tasks_title_search ON tasks(title)",
    "CREATE INDEX idx_tasks_status ON tasks(spaceId, status)",
    "CREATE INDEX idx_tasks_priority ON tasks(spaceId, priority)",
    "CREATE INDEX idx_tasks_dueDate ON tasks(spaceId, dueDate)",
    "CREATE INDEX idx_tasks_estimatedTimeJson ON tasks(spaceId, estimatedTimeJson)",
    "CREATE INDEX idx_tasks_autoUpdateStatusFromSubtasks ON tasks(spaceId, autoUpdateStatusFromSubtasks)",
    "CREATE INDEX idx_tasks_isRecurring ON tasks(spaceId, isRecurring)",
    "CREATE INDEX idx_tasks_notificationsJson ON tasks(spaceId, notificationsJson)",
    """
    CREATE TABLE task_tags (
        taskId TEXT NOT NULL,
        tag TEXT NOT NULL,
        PRIMARY KEY (taskId, tag),
        FOREIGN KEY (taskId) REFERENCES tasks(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_task_tags_tag ON task_tags(tag)",
    "CREATE INDEX idx_task_tags_taskId ON task_tags(taskId)",
    """
    CREATE TABLE task_connections (
        sourceTaskId TEXT NOT NULL,
        targetTaskId TEXT NOT NULL,
        type TEXT NOT NULL,
        PRIMARY KEY (sourceTaskId, targetTaskId, type),
        FOREIGN KEY (sourceTaskId) REFERENCES tasks(id) ON DELETE CASCADE,
        FOREIGN KEY (targetTaskId) REFERENCES tasks(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_connections_source_type ON task_connections(sourceTaskId, type)",
    "CREATE INDEX idx_connections_target ON task_connections(targetTaskId)",
    """
    CREATE TABLE status_changes (
        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
        taskId TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        previousStatusJson TEXT,
        newStatusJson TEXT NOT NULL,
        automaticChangeReasonJson TEXT,
        FOREIGN KEY (taskId) REFERENCES tasks(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_status_changes_taskId_timestamp ON status_changes(taskId, timestamp)",
    """
    CREATE TABLE space_next_ids (
        spaceId TEXT NOT NULL PRIMARY KEY,
        nextId INTEGER NOT NULL,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE tags (
        spaceId TEXT NOT NULL,
        name TEXT NOT NULL,
        PRIMARY KEY (spaceId, name),
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_tags_spaceId ON tags(spaceId)",
    "CREATE INDEX idx_tags_name ON tags(name)",
    """
    CREATE TABLE filter_states (
        spaceId TEXT NOT NULL PRIMARY KEY,
        criteriaJson TEXT NOT NULL,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE view_modes (
        spaceId TEXT NOT NULL PRIMARY KEY,
        viewMode TEXT NOT NULL,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE filter_panel_states (
        spaceId TEXT NOT NULL PRIMARY KEY,
        isOpen INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE custom_view_modes (
        id TEXT NOT NULL,
        spaceId TEXT NOT NULL,
        name TEXT NOT NULL,
        configJson TEXT NOT NULL,
        PRIMARY KEY (spaceId, id),
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_custom_view_modes_spaceId ON custom_view_modes(spaceId)",
    """
    CREATE TABLE active_view_modes (
        spaceId TEXT NOT NULL PRIMARY KEY,
        viewModeId TEXT NOT NULL,
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE saved_filters (
        id TEXT NOT NULL,
        spaceId TEXT NOT NULL,
        name TEXT NOT NULL,
        criteriaJson TEXT NOT NULL,
        viewModeId TEXT,
        PRIMARY KEY (spaceId, id),
        FOREIGN KEY (spaceId) REFERENCES spaces(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX idx_saved_filters_spaceId ON saved_filters(spaceId)",
)
