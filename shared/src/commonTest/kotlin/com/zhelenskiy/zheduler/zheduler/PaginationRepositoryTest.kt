@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.paging.Page
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

class InMemoryPaginationRepositoryTest : PaginationRepositoryTest(), InMemoryRepositoryTest
class DatabasePaginationRepositoryTest : PaginationRepositoryTest(), DatabaseRepositoryTest

/**
 * Every paged query must agree with its whole-list counterpart: same items, same order, same
 * computed totals. That is the contract the UI relies on, and the one thing an offset/limit
 * implementation can quietly break.
 */
abstract class PaginationRepositoryTest : AbstractRepositoryTest {

    private val orderingRules = persistentListOf(
        OrderingRule(OrderableField.TotalDueDate, OrderDirection.Ascending, NullPosition.Last),
        OrderingRule(OrderableField.TotalPriority, OrderDirection.Descending, NullPosition.Last),
        OrderingRule(OrderableField.Id, OrderDirection.Ascending),
    )

    /** Twenty tasks with a spread of due dates, priorities and statuses. */
    private suspend fun TaskRepository.seedTasks(spaceId: String, count: Int = 20) {
        val now = kotlin.time.Clock.System.now()
        repeat(count) { index ->
            addTask(
                spaceId = spaceId,
                title = "Task $index",
                description = "Description of task $index",
                status = if (index % 4 == 0) TaskStatus.Done else TaskStatus.Open,
                dueDate = if (index % 3 == 0) null else now + (index % 7).days,
                priority = if (index % 5 == 0) null else Priority(index % 100),
                tags = if (index % 2 == 0) persistentSetOf("even") else persistentSetOf("odd"),
            )
        }
    }

    /** Read a whole result set through its paged API, one window at a time. */
    private suspend fun <T> readAllPages(
        pageSize: Int,
        loadPage: suspend (offset: Int, limit: Int) -> Page<T>,
    ): List<T> {
        val all = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = loadPage(offset, pageSize)
            all += page.items
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        return all
    }

    // ==================== Grouped task pages ====================

    @Test
    fun `group pages concatenate to the full list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId)

        val full = repo.getTasksForGroup(spaceId, persistentListOf(), orderingRules)

        for (pageSize in listOf(1, 3, 7, 20, 50)) {
            val paged = readAllPages(pageSize) { offset, limit ->
                repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), offset, limit)
            }
            assertEquals(full.map { it.task.id }, paged.map { it.task.id }, "page size $pageSize should preserve order")
        }
    }

    @Test
    fun `group pages report the total and the end of the list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId, count = 12)

        val firstPage = repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), 0, 5)
        assertEquals(5, firstPage.items.size)
        assertEquals(12, firstPage.totalCount)
        assertTrue(firstPage.hasMore)

        val lastPage = repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), 10, 5)
        assertEquals(2, lastPage.items.size)
        assertFalse(lastPage.hasMore)

        val beyondEnd = repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), 50, 5)
        assertTrue(beyondEnd.items.isEmpty())
        assertFalse(beyondEnd.hasMore)
        assertEquals(12, beyondEnd.totalCount)
    }

    @Test
    fun `paged tasks carry the same totals as the full list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        // A dependency chain and a blocker make the totals differ from the tasks' own values.
        val leaf = repo.addTask(spaceId, "Leaf", dueDate = kotlin.time.Clock.System.now() + 10.days, priority = Priority(10))!!
        val middle = repo.addTask(spaceId, "Middle", priority = Priority(20))!!
        val root = repo.addTask(spaceId, "Root", dueDate = kotlin.time.Clock.System.now() + 1.days, priority = Priority(90))!!
        repo.addConnection(leaf.id, middle.id, ConnectionType.IsDependencyOf)
        repo.addConnection(middle.id, root.id, ConnectionType.IsDependencyOf)
        repo.seedTasks(spaceId, count = 5)

        val full = repo.getTasksForGroup(spaceId, persistentListOf(), orderingRules)
            .associateBy { it.task.id }

        val paged = readAllPages(2) { offset, limit ->
            repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), offset, limit)
        }

        assertEquals(full.size, paged.size)
        paged.forEach { pagedTask ->
            val expected = full.getValue(pagedTask.task.id)
            assertEquals(expected.totalDueDate, pagedTask.totalDueDate, "total due date of ${pagedTask.task.id}")
            assertEquals(expected.totalPriority, pagedTask.totalPriority, "total priority of ${pagedTask.task.id}")
            assertEquals(expected.task.connections, pagedTask.task.connections, "connections of ${pagedTask.task.id}")
        }
    }

    @Test
    fun `group pages see tasks added and removed after an earlier page was read`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId, count = 10)
        val noFilters = persistentListOf<GroupFilter>()

        // Read a page first: an implementation that remembers the ranking is primed from here on.
        val before = repo.getTasksForGroupPage(spaceId, noFilters, orderingRules, TaskFilterCriteria(), 0, 5)
        assertEquals(10, before.totalCount)
        assertEquals(10, repo.countTasksForGroup(spaceId, noFilters, TaskFilterCriteria()))

        val added = repo.addTask(spaceId, "Added later", priority = Priority(99))!!

        val afterAdd = repo.getTasksForGroupPage(spaceId, noFilters, orderingRules, TaskFilterCriteria(), 0, 20)
        assertEquals(11, afterAdd.totalCount)
        assertTrue(afterAdd.items.any { it.task.id == added.id }, "a task added after the first page must appear")
        assertEquals(11, repo.countTasksForGroup(spaceId, noFilters, TaskFilterCriteria()))

        repo.deleteTask(added.id)

        val afterDelete = repo.getTasksForGroupPage(spaceId, noFilters, orderingRules, TaskFilterCriteria(), 0, 20)
        assertEquals(10, afterDelete.totalCount)
        assertFalse(afterDelete.items.any { it.task.id == added.id }, "a deleted task must not linger in a page")
        assertEquals(10, repo.countTasksForGroup(spaceId, noFilters, TaskFilterCriteria()))
    }

    @Test
    fun `group pages re-rank after an ordering field changes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val noFilters = persistentListOf<GroupFilter>()
        // Ordered by total priority descending, so the lowest priority sorts last.
        val low = repo.addTask(spaceId, "Low", priority = Priority(1))!!
        repo.addTask(spaceId, "Middle", priority = Priority(50))
        repo.addTask(spaceId, "High", priority = Priority(90))

        val before = repo.getTasksForGroupPage(spaceId, noFilters, orderingRules, TaskFilterCriteria(), 0, 3)
        assertEquals(low.id, before.items.last().task.id, "lowest priority starts last")

        repo.updateTask(repo.getTaskById(low.id)!!.copy(priority = Priority(100)))

        val after = repo.getTasksForGroupPage(spaceId, noFilters, orderingRules, TaskFilterCriteria(), 0, 3)
        assertEquals(low.id, after.items.first().task.id, "raising its priority must move it to the front")
    }

    @Test
    fun `group pages respect filters and their count matches`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId)

        val filters: PersistentList<GroupFilter> = persistentListOf(
            GroupFilter.Values(GroupableField.Status, persistentSetOf("Done"))
        )
        val criteria = TaskFilterCriteria(searchQuery = "Task 1", textSearchFields = persistentSetOf(TaskTextSearchField.Title))

        val full = repo.getTasksForGroup(spaceId, filters, orderingRules, criteria)
        val paged = readAllPages(2) { offset, limit ->
            repo.getTasksForGroupPage(spaceId, filters, orderingRules, criteria, offset, limit)
        }

        assertEquals(full.map { it.task.id }, paged.map { it.task.id })
        assertEquals(full.size, repo.countTasksForGroup(spaceId, filters, criteria))
    }

    @Test
    fun `group counts match the group infos shown in headers`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId)

        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = spaceId,
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done")),
                    )
                )
            ),
            defaultOrderingRules = orderingRules,
        )

        val groups = repo.getTaskGroups(spaceId, viewMode, 0, persistentListOf())
        groups.forEach { group ->
            val filter = group.filter
            val filters = if (filter == null) persistentListOf() else persistentListOf(filter)
            val page = repo.getTasksForGroupPage(spaceId, filters, orderingRules, TaskFilterCriteria(), 0, Int.MAX_VALUE)
            assertEquals(group.taskCount, page.items.size, "header count of '${group.label}'")
            assertEquals(group.taskCount, page.totalCount)
        }
    }

    // ==================== Filtered task pages ====================

    @Test
    fun `filtered task pages concatenate to the full list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId)
        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open))

        val full = repo.getAllWithTotalsFiltered(spaceId, criteria)
        val paged = readAllPages(4) { offset, limit ->
            repo.getAllWithTotalsFilteredPage(spaceId, criteria, offset, limit)
        }

        assertEquals(full.map { it.task.id }, paged.map { it.task.id })
        assertEquals(full.size, repo.countAllWithTotalsFiltered(spaceId, criteria))
    }

    // ==================== Selection and connection search ====================

    @Test
    fun `task selection pages concatenate to the full list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId)
        val excluded = repo.getAllTasks(spaceId).first()

        val full = repo.filterTasksForSelection(spaceId, excluded.id, "Task 1")
        val paged = readAllPages(3) { offset, limit ->
            repo.filterTasksForSelectionPage(spaceId, excluded.id, "Task 1", offset, limit)
        }

        assertEquals(full.map { it.id }, paged.map { it.id })
        assertTrue(paged.none { it.id == excluded.id })
        assertEquals(full.size, repo.filterTasksForSelectionPage(spaceId, excluded.id, "Task 1", 0, 1).totalCount)
    }

    @Test
    fun `connection search pages skip excluded tasks and cycles`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId, count = 10)
        val tasks = repo.getAllTasks(spaceId)
        val current = tasks[0]
        val alreadyConnected = tasks[1].id
        // A subtask of the current task cannot also become its parent.
        repo.addConnection(current.id, tasks[2].id, ConnectionType.ParentOf)

        val full = repo.searchTasksForConnection(
            spaceId = spaceId,
            excludeTaskId = current.id,
            searchQuery = "",
            excludeTaskIds = setOf(alreadyConnected),
            connectionType = ConnectionType.SubtaskOf,
            existingConnections = repo.getTaskById(current.id)!!.connections,
        )

        val paged = readAllPages(3) { offset, limit ->
            repo.searchTasksForConnectionPage(
                spaceId = spaceId,
                excludeTaskId = current.id,
                searchQuery = "",
                excludeTaskIds = setOf(alreadyConnected),
                connectionType = ConnectionType.SubtaskOf,
                existingConnections = repo.getTaskById(current.id)!!.connections,
                offset = offset,
                limit = limit,
            )
        }

        assertEquals(full.map { it.id }, paged.map { it.id })
        assertTrue(paged.none { it.id == current.id || it.id == alreadyConnected || it.id == tasks[2].id })
    }

    // ==================== Spaces and tags ====================

    @Test
    fun `space pages concatenate to the full list`() = runTest {
        val repo = createEmptyRepository()
        repeat(7) { index -> repo.createSpace("Space $index", "SP${('A' + index)}") }

        val full = repo.filterSpaces("", searchInName = true, searchInPrefix = true)
        val paged = readAllPages(2) { offset, limit ->
            repo.filterSpacesPage("", searchInName = true, searchInPrefix = true, offset = offset, limit = limit)
        }

        assertEquals(full.map { it.id }, paged.map { it.id })
        assertEquals(7, paged.size)
    }

    @Test
    fun `searched space pages concatenate to the full list`() = runTest {
        val repo = createEmptyRepository()
        repeat(5) { index -> repo.createSpace("Work $index", "WRK${('A' + index)}") }
        repo.createSpace("Home", "HOME")

        val full = repo.filterSpaces("work", searchInName = true, searchInPrefix = false)
        val paged = readAllPages(2) { offset, limit ->
            repo.filterSpacesPage("work", searchInName = true, searchInPrefix = false, offset = offset, limit = limit)
        }

        assertEquals(full.map { it.id }, paged.map { it.id })
        assertEquals(5, paged.size)
    }

    @Test
    fun `tag pages exclude selected tags and stay in sync with the full list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repeat(10) { index -> repo.addTag(spaceId, "tag-$index") }
        val excluded = setOf("tag-1", "tag-2")

        val full = repo.filterTags(spaceId, "tag", excluded)
        val paged = readAllPages(3) { offset, limit ->
            repo.filterTagsPage(spaceId, "tag", excluded, offset, limit)
        }

        assertEquals(full, paged)
        assertEquals(8, paged.size)
        assertTrue(excluded.none { it in paged })
    }

    @Test
    fun `tag pages work without exclusions`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repeat(4) { index -> repo.addTag(spaceId, "tag-$index") }

        val page = repo.filterTagsPage(spaceId, "", emptySet(), 0, 2)

        assertEquals(listOf("tag-0", "tag-1"), page.items)
        assertEquals(4, page.totalCount)
        assertTrue(page.hasMore)
    }

    // ==================== Change notifications ====================

    @Test
    fun `changes emit after mutations so paged views can reload`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        var emissions = 0
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.changes.collect { emissions++ }
        }
        runCurrent()

        val task = repo.addTask(spaceId, "Task")!!
        runCurrent()
        assertTrue(emissions >= 1, "adding a task should notify")

        val afterAdd = emissions
        repo.updateTask(task.copy(title = "Renamed"))
        runCurrent()
        assertTrue(emissions > afterAdd, "updating a task should notify")

        val afterUpdate = emissions
        repo.deleteTask(task.id)
        runCurrent()
        assertTrue(emissions > afterUpdate, "deleting a task should notify")

        collector.cancel()
    }

    @Test
    fun `reads do not notify`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.seedTasks(spaceId, count = 3)

        var emissions = 0
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.changes.collect { emissions++ }
        }
        runCurrent()

        repo.getTasksForGroupPage(spaceId, persistentListOf(), orderingRules, TaskFilterCriteria(), 0, 2)
        repo.countTasksForGroup(spaceId, persistentListOf(), TaskFilterCriteria())
        runCurrent()

        assertEquals(0, emissions)
        collector.cancel()
    }
}
