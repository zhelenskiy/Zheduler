@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * An index is only worth its write cost if the planner seeks on it, and that depends on the shape
 * of the predicate rather than on the index. A range guarded by `:filterType = 0 OR ...` cannot be
 * seeked on: one compiled plan has to serve every value the parameter might take, so SQLite scans.
 *
 * That is why the filtered query has a variant per range stating it as a plain conjunction, and
 * what makes idx_tasks_dueDate, idx_tasks_priority and idx_tasks_estimatedTimeSeconds worth their
 * write cost. These pin both halves of that reasoning, against the SQLite build the app ships.
 */
class FilterRangeIndexTest {

    private val schema = listOf(
        """
        CREATE TABLE tasks (
          id TEXT NOT NULL PRIMARY KEY,
          spaceId TEXT NOT NULL,
          priority INTEGER,
          dueDate INTEGER,
          estimatedTimeSeconds INTEGER
        )
        """,
        "CREATE INDEX idx_tasks_spaceId ON tasks(spaceId)",
        "CREATE INDEX idx_tasks_priority ON tasks(spaceId, priority)",
        "CREATE INDEX idx_tasks_dueDate ON tasks(spaceId, dueDate)",
        "CREATE INDEX idx_tasks_estimatedTimeSeconds ON tasks(spaceId, estimatedTimeSeconds)",
    )

    /** The plan for [sql] against a table big enough, and varied enough, to be worth indexing. */
    private fun planFor(sql: String): String {
        val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
        return try {
            schema.forEach(connection::execSQL)
            connection.execSQL(
                """
                WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000)
                INSERT INTO tasks SELECT 'T-' || n, 'space-' || (n % 40), (n % 100) + 1,
                         1700000000000 + n * 3600000, (n % 500) * 60 FROM seq
                """
            )
            connection.execSQL("ANALYZE")

            buildString {
                connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                    while (statement.step()) {
                        append(statement.getText(3)).append('\n')
                    }
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a range stated as a conjunction is seeked on`() {
        val plan = planFor(
            """
            SELECT t.* FROM tasks t
            WHERE t.spaceId = 'space-1'
              AND t.estimatedTimeSeconds IS NOT NULL
              AND t.estimatedTimeSeconds >= 1800
              AND t.estimatedTimeSeconds < 3600
            """
        )

        assertTrue("idx_tasks_estimatedTimeSeconds" in plan, "the index should be used: $plan")
        assertTrue("estimatedTimeSeconds>" in plan, "the estimate should narrow the seek: $plan")
    }

    @Test
    fun `a priority range stated as a conjunction is seeked on`() {
        val plan = planFor(
            """
            SELECT t.* FROM tasks t
            WHERE t.spaceId = 'space-1'
              AND t.priority IS NOT NULL AND t.priority >= 50 AND t.priority < 75
            """
        )

        assertTrue("idx_tasks_priority" in plan, "the index should be used: $plan")
        assertTrue("priority>" in plan, "the priority should narrow the seek: $plan")
    }

    @Test
    fun `a due date range stated as a conjunction is seeked on`() {
        val plan = planFor(
            """
            SELECT t.* FROM tasks t
            WHERE t.spaceId = 'space-1'
              AND t.dueDate IS NOT NULL
              AND t.dueDate >= 1700000000000 AND t.dueDate < 1700086400000
            """
        )

        assertTrue("idx_tasks_dueDate" in plan, "the index should be used: $plan")
        assertTrue("dueDate>" in plan, "the due date should narrow the seek: $plan")
    }

    @Test
    fun `the same range guarded by a parameter is not`() {
        val plan = planFor(
            """
            SELECT t.* FROM tasks t
            WHERE t.spaceId = 'space-1'
              AND (
                ?1 = 0
                OR (?1 = 1 AND t.estimatedTimeSeconds IS NULL)
                OR (?1 = 2 AND t.estimatedTimeSeconds >= 1800 AND t.estimatedTimeSeconds < 3600)
              )
            """
        )

        assertTrue(
            "estimatedTimeSeconds>" !in plan,
            "a guarded range cannot narrow the seek, so the conjunctive variant has to exist: $plan",
        )
    }
}
