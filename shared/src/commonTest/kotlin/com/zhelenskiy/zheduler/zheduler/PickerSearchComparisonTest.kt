package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The pickers — spaces, tasks to connect, tags — search with SQL `LIKE` on one side and Kotlin
 * `contains` on the other. A `%` or `_` the user typed is a character to look for, not pattern
 * syntax, and both sides have to agree on that.
 */
@OptIn(ExperimentalTime::class)
class PickerSearchComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    @Test
    fun `a typed percent sign matches only a literal percent`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "50% off")
            repository.addTask(space.id, title = "50 tasks done")

            assertEquals(
                listOf("50% off"),
                repository.searchTasksForConnection(space.id, null, "50%", connectionType = ConnectionType.RelatesTo, existingConnections = emptySet()).map { it.title },
                "$repository: as a wildcard this also matches '50 tasks done'",
            )
        }
    }

    @Test
    fun `a typed underscore matches only a literal underscore`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "a_b naming")
            repository.addTask(space.id, title = "axb naming")

            assertEquals(
                listOf("a_b naming"),
                repository.searchTasksForConnection(space.id, null, "a_b", connectionType = ConnectionType.RelatesTo, existingConnections = emptySet()).map { it.title },
                "$repository: as a wildcard this also matches 'axb naming'",
            )
        }
    }

    @Test
    fun `picker search ignores case outside the ASCII range`() = runTest {
        // SQLite's own LOWER folds nothing but A-Z, so a query typed in lower case found nothing
        // in any language whose alphabet is not English, while the in-memory side matched.
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "Купить хлеб")
            repository.addTask(space.id, title = "Something else")

            assertEquals(
                listOf("Купить хлеб"),
                repository.searchTasksForConnection(space.id, null, "купить", connectionType = ConnectionType.RelatesTo, existingConnections = emptySet()).map { it.title },
                "$repository",
            )
        }
    }

    @Test
    fun `tag search treats wildcards as characters`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTag(space.id, "100%")
            repository.addTag(space.id, "100 percent")

            assertEquals(
                listOf("100%"),
                repository.filterTags(space.id, "100%", emptySet()),
                "$repository",
            )
        }
    }

    @Test
    fun `space search treats wildcards as characters`() = runTest {
        for (repository in repositories()) {
            repository.createSpace("Q1 100% done", "AAA")
            repository.createSpace("Q1 100 items", "BBB")

            assertEquals(
                listOf("Q1 100% done"),
                repository.filterSpaces("100%", searchInName = true, searchInPrefix = false).map { it.name },
                "$repository",
            )
        }
    }

    @Test
    fun `tag search ignores case outside the ASCII range`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTag(space.id, "Дом")
            repository.addTag(space.id, "work")

            assertEquals(listOf("Дом"), repository.filterTags(space.id, "дом", emptySet()), "$repository")
        }
    }

    @Test
    fun `space search ignores case outside the ASCII range`() = runTest {
        for (repository in repositories()) {
            repository.createSpace("Работа", "AAA")
            repository.createSpace("Home", "BBB")

            assertEquals(
                listOf("Работа"),
                repository.filterSpaces("работа", searchInName = true, searchInPrefix = false).map { it.name },
                "$repository",
            )
        }
    }

    @Test
    fun `an ordinary search still works`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "write the report")
            repository.addTask(space.id, title = "read the report")

            assertEquals(
                setOf("write the report", "read the report"),
                repository.searchTasksForConnection(space.id, null, "report", connectionType = ConnectionType.RelatesTo, existingConnections = emptySet()).mapTo(mutableSetOf()) { it.title },
                "$repository",
            )
        }
    }
}
