@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import androidx.paging.PagingSource
import com.zhelenskiy.zheduler.zheduler.paging.OffsetPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.Page
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * Bugs a review turned up, each of which shipped because nothing compared the two repositories on
 * this particular criterion. The filter panel's criteria have to mean the same thing whichever
 * path answers them, so every case here asserts in-memory against the database.
 */
class FilterCriteriaComparisonTest {

    private suspend fun both(): Pair<TaskRepository, TaskRepository> =
        InMemoryTaskRepository() to createDatabaseRepository()

    /** the Room grouped path ignores declinedReason. */
    @Test
    fun claim_declinedReason() = runTest {
        val (mem, db) = both()
        listOf(mem, db).forEach { repo ->
            val spaceId = repo.createSpace("Test", "TEST")!!.id
            repo.addTask(spaceId, title = "obsolete", status = TaskStatus.Declined("obsolete"))
            repo.addTask(spaceId, title = "wontfix", status = TaskStatus.Declined("wontfix"))
        }
        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Declined("")),
            declinedReason = "obsolete",
        )
        val m = mem.getTasksForGroup(mem.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        val d = db.getTasksForGroup(db.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        assertEquals(m.map { it.task.title }.toSet(), d.map { it.task.title }.toSet(), "declinedReason")
    }

    /** the Room grouped path ignores the connection-id filters. */
    @Test
    fun claim_connectionIdFilter() = runTest {
        val (mem, db) = both()
        listOf(mem, db).forEach { repo ->
            val spaceId = repo.createSpace("Test", "TEST")!!.id
            val target = repo.addTask(spaceId, title = "target")!!
            repo.addTask(
                spaceId, title = "depends on target",
                connections = persistentSetOf(TaskConnection(target.id, ConnectionType.DependsOn)),
            )
            repo.addTask(spaceId, title = "unrelated")
        }
        val criteria = TaskFilterCriteria(dependsOnTaskIds = "TEST-1")
        val m = mem.getTasksForGroup(mem.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        val d = db.getTasksForGroup(db.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        assertEquals(m.map { it.task.title }.toSet(), d.map { it.task.title }.toSet(), "dependsOnTaskIds")
    }

    /** the Room grouped path ignores blockedByComment. */
    @Test
    fun claim_blockedByComment() = runTest {
        val (mem, db) = both()
        listOf(mem, db).forEach { repo ->
            val spaceId = repo.createSpace("Test", "TEST")!!.id
            val blocker = repo.addTask(spaceId, title = "blocker")!!
            repo.addTask(
                spaceId, title = "waiting on legal",
                status = TaskStatus.Blocked(persistentSetOf(blocker.id), "legal review"),
            )
            repo.addTask(
                spaceId, title = "waiting on design",
                status = TaskStatus.Blocked(persistentSetOf(blocker.id), "design review"),
            )
        }
        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByComment = "legal",
        )
        val m = mem.getTasksForGroup(mem.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        val d = db.getTasksForGroup(db.getAllSpaces().single().id, persistentListOf(), persistentListOf(), criteria)
        assertEquals(m.map { it.task.title }.toSet(), d.map { it.task.title }.toSet(), "blockedByComment")
    }

    /** removing a SubtaskOf link via updateTask leaves the old parent's auto status stale in Room. */
    @Test
    fun claim_removingSubtaskDoesNotRestateParent() = runTest {
        suspend fun run(repo: TaskRepository): TaskStatus {
            val spaceId = repo.createSpace("Test", "TEST")!!.id
            val parent = repo.addTask(spaceId, title = "Parent", autoUpdateStatusFromSubtasks = true)!!
            val child = repo.addTask(
                spaceId, title = "Child", status = TaskStatus.Done,
                connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf)),
            )!!
            val other = repo.addTask(
                spaceId, title = "Other", status = TaskStatus.Open,
                connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf)),
            )!!
            // Detach the Open child; the parent should become Done (its only subtask left is Done).
            repo.updateTask(repo.getTaskById(other.id)!!.copy(connections = persistentSetOf()))
            return repo.getTaskById(parent.id)!!.status
        }
        val (mem, db) = both()
        assertEquals(run(mem)::class.simpleName, run(db)::class.simpleName, "parent status after detaching a subtask")
    }

    /** nested grouping on the same field replaces the parent's range instead of ANDing. */
    @Test
    fun claim_nestedSameFieldGrouping() = runTest {
        val (mem, db) = both()
        listOf(mem, db).forEach { repo ->
            val spaceId = repo.createSpace("Test", "TEST")!!.id
            repo.addTask(spaceId, title = "p10", priority = Priority(10))
            repo.addTask(spaceId, title = "p90", priority = Priority(90))
        }
        // Parent group: high priority. Child group: low priority. Nothing matches both.
        val filters = persistentListOf<GroupFilter>(
            GroupFilter.PriorityRange(min = 75, max = 100),
            GroupFilter.PriorityRange(min = 1, max = 49),
        )
        val m = mem.getTasksForGroup(mem.getAllSpaces().single().id, filters, persistentListOf())
        val d = db.getTasksForGroup(db.getAllSpaces().single().id, filters, persistentListOf())
        assertEquals(m.map { it.task.title }.toSet(), d.map { it.task.title }.toSet(), "nested same-field groups")
    }

    /** A prepend must join the page it precedes, whatever size the refresh used. */
    @Test
    fun pagingPrependIsContiguousWithTheRefresh() = runTest {
        val all = (0 until 200).map { "item-$it" }
        val source = OffsetPagingSource { offset, limit ->
            Page(
                items = all.subList(offset.coerceAtMost(all.size), (offset + limit).coerceAtMost(all.size)),
                offset = offset,
                totalCount = all.size,
            )
        }

        // A refresh loads initialLoadSize (60); prepends afterwards load pageSize (30).
        val refresh = source.load(PagingSource.LoadParams.Refresh(90, 60, false))
                as PagingSource.LoadResult.Page
        assertEquals("item-90", refresh.data.first())

        val prepend = source.load(PagingSource.LoadParams.Prepend(refresh.prevKey!!, 30, false))
                as PagingSource.LoadResult.Page

        // No gap: the prepended page has to end on the item before the refresh page begins.
        assertEquals("item-89", prepend.data.last(), "items between the two pages were never loaded")
        assertEquals("item-60", prepend.data.first())

        // And again, walking further back.
        val second = source.load(PagingSource.LoadParams.Prepend(prepend.prevKey!!, 30, false))
                as PagingSource.LoadResult.Page
        assertEquals("item-59", second.data.last())
        assertEquals("item-30", second.data.first())
    }

    /** A "fifth weekday" pattern must skip months that have no fifth, not fail. */
    @Test
    fun fifthWeekdaySkipsMonthsWithoutOne() {
        // June 2026's fifth Monday is the 29th; July 2026 has only four.
        val from = LocalDateTime(2026, 6, 30, 9, 0).toInstant(TimeZone.UTC)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.NthDayOfWeekInMonth(WeekOrdinal.FIFTH, RecurrenceDayOfWeek.MONDAY),
                startFrom = from,
            ),
            statusChangeTrigger = null,
            resetToStatus = TaskStatus.Open,
        )

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), from)

        // The next fifth Monday after 30 June 2026 is 31 August 2026.
        assertEquals(
            LocalDateTime(2026, 8, 31, 0, 0).toInstant(TimeZone.currentSystemDefault()),
            next,
        )
    }
}
