package com.zhelenskiy.zheduler.zheduler.db

import androidx.sqlite.execSQL
import com.zhelenskiy.zheduler.worker.createSQLiteWasmWorker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The point of the web migration: a database opened by file name lives in the Origin Private File
 * System and outlives the connection that wrote it. Everything else in the browser suites runs
 * against `:memory:`, so without this test the persistent path would never be executed.
 *
 * Deliberately driver-level rather than through Room: `RoomDatabase.close()` spin-waits on a
 * barrier and deadlocks the browser's single thread (see TestUtils.web.kt), and closing is exactly
 * what this test needs to do.
 */
class WebOpfsPersistenceTest {

    @Test
    fun opfsDatabaseSurvivesReopening() = runTest {
        val driver = createSQLiteWasmWorker()
        val fileName = "zheduler-opfs-probe.db"

        val first = driver.open(fileName)
        first.execSQL("DROP TABLE IF EXISTS probe")
        first.execSQL("CREATE TABLE probe (value TEXT NOT NULL)")
        first.execSQL("INSERT INTO probe(value) VALUES ('persisted')")
        first.close()

        val second = driver.open(fileName)
        val statement = second.prepare("SELECT value FROM probe")
        val value = if (statement.step()) statement.getText(0) else null
        statement.close()
        // Leave the origin clean for the next run.
        second.execSQL("DROP TABLE probe")
        second.close()

        assertEquals("persisted", value)
    }
}
