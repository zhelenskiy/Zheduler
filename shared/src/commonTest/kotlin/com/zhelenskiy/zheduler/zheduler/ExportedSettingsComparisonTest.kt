package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A backup is of the space, not only of its tasks.
 *
 * View modes and saved filters are the arrangement the user built around the tasks, and they went
 * down with the space on delete while the export carried no trace of them: restoring the file gave
 * back every task and none of the work of organising them.
 */
@OptIn(ExperimentalTime::class)
class ExportedSettingsComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** Builds a space with a view mode and a filter attached to it, then exports it. */
    private suspend fun TaskRepository.exportedSpace(): Pair<String, String> {
        val space = createSpace("Source", "SRC")!!
        val task = addTask(space.id, title = "a task")!!
        val mode = saveViewMode(
            ViewMode(
                id = "vm-original",
                name = "By status",
                spaceId = space.id,
                groupingLevels = persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Status,
                        groups = persistentListOf(
                            GroupDefinition(label = "Open", values = persistentSetOf("Open")),
                        ),
                    )
                ),
            )
        )
        saveSavedFilter(
            SavedFilter(
                id = "filter-original",
                name = "Waiting on the first task",
                spaceId = space.id,
                criteria = TaskFilterCriteria(dependsOnTaskIds = task.id),
                viewModeId = mode.id,
            )
        )
        return space.id to assertNotNull(exportSpaceToJson(space.id, prettyPrint = false))
    }

    @Test
    fun `an imported space brings its view modes and filters with it`() = runTest {
        for (repository in repositories()) {
            val (sourceId, json) = repository.exportedSpace()
            assertEquals(true, repository.deleteSpace(sourceId), "$repository: could not delete")

            val imported = assertNotNull(repository.importSpaceFromJson(json), "$repository: import failed")

            val modes = repository.getAllViewModes(imported.id).filter { !it.isBuiltIn }
            assertEquals(listOf("By status"), modes.map { it.name }, "$repository")

            val filters = repository.getAllSavedFilters(imported.id)
            assertEquals(listOf("Waiting on the first task"), filters.map { it.name }, "$repository")
            assertEquals(
                modes.single().id,
                filters.single().viewModeId,
                "$repository: the filter points at the view mode that came with it",
            )
        }
    }

    @Test
    fun `a filter's task ids follow the tasks into the new space`() = runTest {
        for (repository in repositories()) {
            val (_, json) = repository.exportedSpace()

            // Imported alongside the original, so every id has to be minted afresh.
            val imported = assertNotNull(repository.importSpaceFromJson(json), "$repository: import failed")
            val importedTask = repository.getAllTasks(imported.id).single()

            assertEquals(
                importedTask.id,
                repository.getAllSavedFilters(imported.id).single().criteria.dependsOnTaskIds,
                "$repository: the filter still names the task id from the space it came from",
            )
        }
    }

    @Test
    fun `importing beside the original does not collide`() = runTest {
        for (repository in repositories()) {
            val (sourceId, json) = repository.exportedSpace()

            val imported = assertNotNull(repository.importSpaceFromJson(json), "$repository: import failed")

            assertEquals(1, repository.getAllSavedFilters(sourceId).size, "$repository: the original lost a filter")
            assertEquals(1, repository.getAllSavedFilters(imported.id).size, "$repository")
        }
    }
}
