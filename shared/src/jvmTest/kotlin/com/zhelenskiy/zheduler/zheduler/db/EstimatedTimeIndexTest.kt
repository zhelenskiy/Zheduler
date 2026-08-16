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
 * That is why the filtered query has a variant stating the range as a plain conjunction, and why
 * `idx_tasks_estimatedTimeSeconds` exists at all. These pin both halves of that reasoning, against
 * the same SQLite build the app ships.
 */
class EstimatedTimeIndexTest {

    private val schema = listOf(
        """
        CREATE TABLE tasks (
          id TEXT NOT NULL PRIMARY KEY,
          spaceId TEXT NOT NULL,
          estimatedTimeSeconds INTEGER
        )
        """,
        "CREATE INDEX idx_tasks_spaceId ON tasks(spaceId)",
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
                INSERT INTO tasks SELECT 'T-' || n, 'space-' || (n % 40), (n % 500) * 60 FROM seq
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
