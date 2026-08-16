package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Importing a backup restores what was exported.
 *
 * Connections are added one at a time, so a parent that derives its status from its subtasks sees
 * them arrive one at a time too. Recomputing as they land makes the parent pass through statuses it
 * never held — and each of those is a real status change to everything downstream, which is how a
 * task exported as Blocked came back unblocked.
 */
@OptIn(ExperimentalTime::class)
class ImportStatusFidelityComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** The export used by both cases: statuses keyed by title, so ids may be remapped freely. */
    private suspend fun exportedSpace(): String {
        val source = InMemoryTaskRepository(Clock.System)
        val space = source.createSpace("Source", "SRC")!!
        val done = source.addTask(space.id, title = "done subtask")!!
        val open = source.addTask(space.id, title = "open subtask")!!
        val parent = source.addTask(space.id, title = "parent", autoUpdateStatusFromSubtasks = true)!!
        source.addConnection(done.id, parent.id, ConnectionType.SubtaskOf)
        source.addConnection(open.id, parent.id, ConnectionType.SubtaskOf)
        source.updateTask(source.getTaskById(done.id)!!.copy(status = TaskStatus.Done))

        val waiting = source.addTask(space.id, title = "waiting on the parent")!!
        source.updateTask(
            source.getTaskById(waiting.id)!!
                .copy(status = TaskStatus.Blocked(kotlinx.collections.immutable.persistentSetOf(parent.id), "later"))
        )

        return assertNotNull(source.exportSpaceToJson(space.id, prettyPrint = false))
    }

    /** Statuses of the space [json] imports as, keyed by title. */
    private suspend fun TaskRepository.importedStatuses(json: String): Map<String, TaskStatus> {
        val imported = assertNotNull(importSpaceFromJson(json), "$this: import failed")
        return getAllTasks(imported.id).associate { it.title to it.status }
    }

    @Test
    fun `both repositories import the same file the same way`() = runTest {
        val json = exportedSpace()
        val (inMemory, database) = repositories()

        assertEquals(
            inMemory.importedStatuses(json).mapValues { (_, status) -> status::class },
            database.importedStatuses(json).mapValues { (_, status) -> status::class },
        )
    }

    @Test
    fun `importing writes no status history of its own`() = runTest {
        val json = exportedSpace()

        for (repository in repositories()) {
            val imported = assertNotNull(repository.importSpaceFromJson(json), "$repository: import failed")
            val parent = repository.getAllTasks(imported.id).single { it.title == "parent" }
            val reasons = repository.getStatusTimeline(parent.id).mapNotNull { it.automaticChangeReason }

            assertEquals(
                emptyList(),
                reasons,
                "$repository: the import invented status changes for a task it only had to restore",
            )
        }
    }
}
