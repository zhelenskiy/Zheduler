@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

class InMemoryTaskFiltersRepositoryTest : TaskFiltersRepositoryTest(), InMemoryRepositoryTest
class DatabaseTaskFiltersRepositoryTest : TaskFiltersRepositoryTest(), DatabaseRepositoryTest


abstract class TaskFiltersRepositoryTest : AbstractRepositoryTest {


    // ==================== TaskFilterCriteria Tests ====================

    @Test
    fun `TaskFilterCriteria default has no active filters`() {
        val criteria = TaskFilterCriteria()
        assertFalse(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with searchQuery has active filters`() {
        val criteria = TaskFilterCriteria(searchQuery = "test")
        assertTrue(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with blank searchQuery has no active filters`() {
        val criteria = TaskFilterCriteria(searchQuery = "   ")
        assertFalse(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with statusFilters has active filters`() {
        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open))
        assertTrue(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with dueDateFilter has active filters`() {
        val criteria = TaskFilterCriteria(dueDateFilter = DueDateFilter.Today)
        assertTrue(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with priorityFilter has active filters`() {
        val criteria = TaskFilterCriteria(priorityFilter = PriorityFilter.High)
        assertTrue(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with selectedTags has active filters`() {
        val criteria = TaskFilterCriteria(selectedTags = persistentSetOf("tag1"))
        assertTrue(criteria.hasActiveFilters)
    }

    @Test
    fun `TaskFilterCriteria with connectionTypeFilters has active filters`() {
        val criteria = TaskFilterCriteria(connectionTypeFilters = persistentSetOf(ConnectionTypeOption.DependsOn))
        assertTrue(criteria.hasActiveFilters)
    }

    // ==================== Text Search Filter Tests ====================

    @Test
    fun `filter by title finds matching tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Important task")
        repo.addTask(spaceId, title = "Another task")
        repo.addTask(spaceId, title = "Something else")

        val criteria = TaskFilterCriteria(
            searchQuery = "important",
            textSearchFields = persistentSetOf(TaskTextSearchField.Title)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Important task", results.first().task.title)
    }

    @Test
    fun `filter by title is case insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "UPPERCASE TASK")
        repo.addTask(spaceId, title = "lowercase task")

        val criteria = TaskFilterCriteria(
            searchQuery = "TASK",
            textSearchFields = persistentSetOf(TaskTextSearchField.Title)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
    }

    @Test
    fun `filter by ID finds matching tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1")
        repo.addTask(spaceId, title = "Task 2")
        repo.addTask(spaceId, title = "Task 3")

        val criteria = TaskFilterCriteria(
            searchQuery = "TEST-2",
            textSearchFields = persistentSetOf(TaskTextSearchField.Id)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("TEST-2", results.first().task.id)
    }

    @Test
    fun `filter by tags finds matching tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", tags = persistentSetOf("urgent", "bug"))
        repo.addTask(spaceId, title = "Task 2", tags = persistentSetOf("feature"))
        repo.addTask(spaceId, title = "Task 3", tags = persistentSetOf("urgent", "feature"))

        val criteria = TaskFilterCriteria(
            searchQuery = "urgent",
            textSearchFields = persistentSetOf(TaskTextSearchField.Tags)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
    }

    @Test
    fun `filter by description finds matching tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", description = "This needs review before merge")
        repo.addTask(spaceId, title = "Task 2", description = "Simple fix")

        val criteria = TaskFilterCriteria(
            searchQuery = "review",
            textSearchFields = persistentSetOf(TaskTextSearchField.Description)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
    }

    @Test
    fun `filter searches across multiple fields`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Match in title")
        repo.addTask(spaceId, title = "Other", description = "Match in description")
        repo.addTask(spaceId, title = "Third", tags = persistentSetOf("match"))

        val criteria = TaskFilterCriteria(
            searchQuery = "match",
            textSearchFields = persistentSetOf(
                TaskTextSearchField.Title,
                TaskTextSearchField.Description,
                TaskTextSearchField.Tags
            )
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(3, results.size)
    }

    @Test
    fun `filter with multiple words matches all words`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Important urgent task")
        repo.addTask(spaceId, title = "Important task")
        repo.addTask(spaceId, title = "Urgent task")

        val criteria = TaskFilterCriteria(
            searchQuery = "important urgent",
            textSearchFields = persistentSetOf(TaskTextSearchField.Title)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Important urgent task", results.first().task.title)
    }

    // ==================== Status Filter Tests ====================

    @Test
    fun `filter by single status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Open task")
        repo.addTask(spaceId, title = "Done task", status = TaskStatus.Done)
        repo.addTask(spaceId, title = "In progress", status = TaskStatus.InProgress)

        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals(TaskStatus.Open, results.first().task.status)
    }

    @Test
    fun `filter by multiple statuses`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Open task")
        repo.addTask(spaceId, title = "Done task", status = TaskStatus.Done)
        repo.addTask(spaceId, title = "In progress", status = TaskStatus.InProgress)

        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open, TaskStatus.InProgress))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
    }

    @Test
    fun `filter by Blocked status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        repo.addTask(spaceId, title = "Blocked task", status = TaskStatus.Blocked(persistentSetOf(blocker.id)))
        repo.addTask(spaceId, title = "Open task")

        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertIs<TaskStatus.Blocked>(results.first().task.status)
    }

    @Test
    fun `filter by Declined status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Declined task", status = TaskStatus.Declined("Not needed"))
        repo.addTask(spaceId, title = "Open task")

        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Declined("")))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertIs<TaskStatus.Declined>(results.first().task.status)
    }

    @Test
    fun `filter Blocked status by specific blocker task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        repo.addTask(spaceId, title = "Blocked by 1", status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "waiting"))
        repo.addTask(spaceId, title = "Blocked by 2", status = TaskStatus.Blocked(persistentSetOf(blocker2.id), "pending"))
        repo.addTask(
            spaceId,
            title = "Blocked by both",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id), "")
        )
        repo.addTask(spaceId, title = "Open task")

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByTaskIds = blocker1.id
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.all { it.task.title.contains("1") || it.task.title.contains("both") })
    }

    @Test
    fun `filter Blocked status by multiple blocker task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocker3 = repo.addTask(spaceId, title = "Blocker 3")!!
        repo.addTask(spaceId, title = "Blocked by 1", status = TaskStatus.Blocked(persistentSetOf(blocker1.id)))
        repo.addTask(spaceId, title = "Blocked by 2", status = TaskStatus.Blocked(persistentSetOf(blocker2.id)))
        repo.addTask(spaceId, title = "Blocked by 3", status = TaskStatus.Blocked(persistentSetOf(blocker3.id)))

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByTaskIds = "${blocker1.id}, ${blocker2.id}"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.none { it.task.title.contains("3") })
    }

    @Test
    fun `filter Blocked status by comment text`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        repo.addTask(spaceId, title = "Blocked 1", status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting for review"))
        repo.addTask(spaceId, title = "Blocked 2", status = TaskStatus.Blocked(persistentSetOf(blocker.id), "pending approval"))
        repo.addTask(
            spaceId,
            title = "Blocked 3",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting for deployment")
        )
        repo.addTask(spaceId, title = "Open task")

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByComment = "waiting"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.all { it.task.title.contains("1") || it.task.title.contains("3") })
    }

    @Test
    fun `filter Blocked status by comment is case insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        repo.addTask(spaceId, title = "Blocked 1", status = TaskStatus.Blocked(persistentSetOf(blocker.id), "WAITING FOR REVIEW"))
        repo.addTask(spaceId, title = "Blocked 2", status = TaskStatus.Blocked(persistentSetOf(blocker.id), "pending"))

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByComment = "waiting"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Blocked 1", results.first().task.title)
    }

    @Test
    fun `filter Blocked status by both IDs and comment`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        repo.addTask(
            spaceId,
            title = "Match both",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "waiting for review")
        )
        repo.addTask(spaceId, title = "ID only", status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "pending"))
        repo.addTask(
            spaceId,
            title = "Comment only",
            status = TaskStatus.Blocked(persistentSetOf(blocker2.id), "waiting for approval")
        )

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Blocked(persistentSetOf())),
            blockedByTaskIds = blocker1.id,
            blockedByComment = "waiting"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Match both", results.first().task.title)
    }

    @Test
    fun `filter Declined status by reason text`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Declined 1", status = TaskStatus.Declined("Not needed anymore"))
        repo.addTask(spaceId, title = "Declined 2", status = TaskStatus.Declined("Duplicate task"))
        repo.addTask(spaceId, title = "Declined 3", status = TaskStatus.Declined("Not feasible"))
        repo.addTask(spaceId, title = "Open task")

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Declined("")),
            declinedReason = "not"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.all { it.task.title.contains("1") || it.task.title.contains("3") })
    }

    @Test
    fun `filter Declined status by reason is case insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Declined 1", status = TaskStatus.Declined("NOT NEEDED"))
        repo.addTask(spaceId, title = "Declined 2", status = TaskStatus.Declined("duplicate"))

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Declined("")),
            declinedReason = "not needed"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Declined 1", results.first().task.title)
    }

    // ==================== Due Date Filter Tests ====================

    @Test
    fun `filter by NoDueDate finds tasks without due date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "No due date")
        repo.addTask(spaceId, title = "Has due date", dueDate = Clock.System.now() + 1.days)

        val criteria = TaskFilterCriteria(dueDateFilter = DueDateFilter.NoDueDate)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertNull(results.first().task.dueDate)
    }

    @Test
    fun `filter by Overdue finds past due tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Overdue", dueDate = Clock.System.now() - 1.days)
        repo.addTask(spaceId, title = "Future", dueDate = Clock.System.now() + 1.days)
        repo.addTask(spaceId, title = "No due date")

        val criteria = TaskFilterCriteria(dueDateFilter = DueDateFilter.Overdue)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Overdue", results.first().task.title)
    }

    @Test
    fun `filter by Today finds tasks due today`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        repo.addTask(spaceId, title = "Due today", dueDate = now)
        repo.addTask(spaceId, title = "Due tomorrow", dueDate = now + 1.days)
        repo.addTask(spaceId, title = "Due yesterday", dueDate = now - 1.days)

        val criteria = TaskFilterCriteria(dueDateFilter = DueDateFilter.Today)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        // Result depends on time of day, should have at least the today task
        assertTrue(results.any { it.task.title == "Due today" })
    }

    // ==================== Priority Filter Tests ====================

    @Test
    fun `filter by High priority`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "High priority", priority = Priority(80))
        repo.addTask(spaceId, title = "Medium priority", priority = Priority(50))
        repo.addTask(spaceId, title = "Low priority", priority = Priority(20))

        val criteria = TaskFilterCriteria(priorityFilter = PriorityFilter.High)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("High priority", results.first().task.title)
    }

    @Test
    fun `filter by Medium priority`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "High priority", priority = Priority(80))
        repo.addTask(spaceId, title = "Medium priority", priority = Priority(60))
        repo.addTask(spaceId, title = "Low priority", priority = Priority(20))

        val criteria = TaskFilterCriteria(priorityFilter = PriorityFilter.Medium)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Medium priority", results.first().task.title)
    }

    @Test
    fun `filter by Low priority`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "High priority", priority = Priority(80))
        repo.addTask(spaceId, title = "Medium priority", priority = Priority(60))
        repo.addTask(spaceId, title = "Low priority", priority = Priority(20))

        val criteria = TaskFilterCriteria(priorityFilter = PriorityFilter.Low)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Low priority", results.first().task.title)
    }

    @Test
    fun `filter by NoPriority finds tasks without priority`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Has priority", priority = Priority(50))
        repo.addTask(spaceId, title = "No priority")

        val criteria = TaskFilterCriteria(priorityFilter = PriorityFilter.NoPriority)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("No priority", results.first().task.title)
    }

    @Test
    fun `filter by custom priority range`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Priority 30", priority = Priority(30))
        repo.addTask(spaceId, title = "Priority 50", priority = Priority(50))
        repo.addTask(spaceId, title = "Priority 70", priority = Priority(70))

        val criteria = TaskFilterCriteria(
            priorityFilter = PriorityFilter.Custom,
            customPriorityMin = "40",
            customPriorityMax = "60"
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Priority 50", results.first().task.title)
    }

    // ==================== Estimated Time Filter Tests ====================

    @Test
    fun `filter by NoEstimate finds tasks without estimated time`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Has estimate", estimatedTime = RecurrencePeriod(hours = 2))
        repo.addTask(spaceId, title = "No estimate")

        val criteria = TaskFilterCriteria(estimatedTimeFilter = EstimatedTimeFilter.NoEstimate)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("No estimate", results.first().task.title)
    }

    @Test
    fun `filter by Quick estimate finds tasks under 15 minutes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Quick task", estimatedTime = RecurrencePeriod(minutes = 10))
        repo.addTask(spaceId, title = "Long task", estimatedTime = RecurrencePeriod(hours = 2))

        val criteria = TaskFilterCriteria(estimatedTimeFilter = EstimatedTimeFilter.Quick)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Quick task", results.first().task.title)
    }

    // ==================== Tag Filter Tests ====================

    @Test
    fun `filter by tags with Match Any mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", tags = persistentSetOf("bug"))
        repo.addTask(spaceId, title = "Task 2", tags = persistentSetOf("feature"))
        repo.addTask(spaceId, title = "Task 3", tags = persistentSetOf("bug", "feature"))
        repo.addTask(spaceId, title = "Task 4", tags = persistentSetOf("docs"))

        val criteria = TaskFilterCriteria(
            selectedTags = persistentSetOf("bug", "feature"),
            tagMatchMode = TagMatchMode.Any
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(3, results.size)
    }

    @Test
    fun `filter by tags with Match All mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", tags = persistentSetOf("bug"))
        repo.addTask(spaceId, title = "Task 2", tags = persistentSetOf("feature"))
        repo.addTask(spaceId, title = "Task 3", tags = persistentSetOf("bug", "feature"))
        repo.addTask(spaceId, title = "Task 4", tags = persistentSetOf("bug", "feature", "urgent"))

        val criteria = TaskFilterCriteria(
            selectedTags = persistentSetOf("bug", "feature"),
            tagMatchMode = TagMatchMode.All
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.all { it.task.tags.containsAll(setOf("bug", "feature")) })
    }

    // ==================== Recurrence Filter Tests ====================

    @Test
    fun `filter by NoRecurrence finds non-recurring tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Non-recurring")
        repo.addTask(
            spaceId,
            title = "Recurring",
            recurrenceRules = persistentListOf(RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofDays(1),
                    firstOccurrence = Clock.System.now()
                ),
                statusChangeTrigger = null,
                resetToStatus = TaskStatus.Open,
            ).to(RecurrenceState()))
        )

        val criteria = TaskFilterCriteria(recurrenceFilter = RecurrenceFilter.NoRecurrence)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Non-recurring", results.first().task.title)
    }

    @Test
    fun `filter by HasRecurrence finds recurring tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Non-recurring")
        repo.addTask(
            spaceId,
            title = "Recurring",
            recurrenceRules = persistentListOf(RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofDays(1),
                    firstOccurrence = Clock.System.now()
                ),
                statusChangeTrigger = null,
                resetToStatus = TaskStatus.Open,
            ).to(RecurrenceState()))
        )

        val criteria = TaskFilterCriteria(recurrenceFilter = RecurrenceFilter.HasRecurrence)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Recurring", results.first().task.title)
    }

    // ==================== Notifications Filter Tests ====================

    @Test
    fun `filter by NoNotifications finds tasks without notifications`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "No notifications")
        repo.addTask(
            spaceId,
            title = "Has notifications",
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1)))
        )

        val criteria = TaskFilterCriteria(notificationsFilter = NotificationsFilter.NoNotifications)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("No notifications", results.first().task.title)
    }

    @Test
    fun `filter by HasNotifications finds tasks with notifications`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "No notifications")
        repo.addTask(
            spaceId,
            title = "Has notifications",
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1)))
        )

        val criteria = TaskFilterCriteria(notificationsFilter = NotificationsFilter.HasNotifications)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Has notifications", results.first().task.title)
    }

    // ==================== Auto Update Status Filter Tests ====================

    @Test
    fun `filter by Auto finds tasks with autoUpdateStatusFromSubtasks enabled`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Manual")
        repo.addTask(spaceId, title = "Auto", autoUpdateStatusFromSubtasks = true)

        val criteria = TaskFilterCriteria(autoUpdateStatusFilter = AutoUpdateStatusFilter.Auto)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Auto", results.first().task.title)
    }

    @Test
    fun `filter by Manual finds tasks with autoUpdateStatusFromSubtasks disabled`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Manual")
        repo.addTask(spaceId, title = "Auto", autoUpdateStatusFromSubtasks = true)

        val criteria = TaskFilterCriteria(autoUpdateStatusFilter = AutoUpdateStatusFilter.Manual)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Manual", results.first().task.title)
    }

    // ==================== Connection Type Filter Tests ====================

    @Test
    fun `filter by DependsOn finds tasks with dependencies`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val dependent = repo.addTask(spaceId, title = "Dependent")!!
        repo.addTask(spaceId, title = "Independent")

        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val criteria = TaskFilterCriteria(connectionTypeFilters = persistentSetOf(ConnectionTypeOption.DependsOn))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Dependent", results.first().task.title)
    }

    @Test
    fun `filter by ParentOf finds tasks with subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child = repo.addTask(spaceId, title = "Child")!!
        repo.addTask(spaceId, title = "Standalone")

        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        val criteria = TaskFilterCriteria(connectionTypeFilters = persistentSetOf(ConnectionTypeOption.ParentOf))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Parent", results.first().task.title)
    }

    @Test
    fun `filter by NotSubtask finds tasks that are not subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child = repo.addTask(spaceId, title = "Child")!!
        repo.addTask(spaceId, title = "Standalone")

        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        val criteria = TaskFilterCriteria(connectionTypeFilters = persistentSetOf(ConnectionTypeOption.NotSubtask))
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.none { it.task.title == "Child" })
    }

    @Test
    fun `filter by specific DependsOn task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val dependent1 = repo.addTask(spaceId, title = "Dependent 1")!!
        val dependent2 = repo.addTask(spaceId, title = "Dependent 2")!!
        repo.addTask(spaceId, title = "Independent")

        repo.addConnection(dependent1.id, blocker1.id, ConnectionType.DependsOn)
        repo.addConnection(dependent2.id, blocker2.id, ConnectionType.DependsOn)

        val criteria = TaskFilterCriteria(dependsOnTaskIds = blocker1.id)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Dependent 1", results.first().task.title)
    }

    @Test
    fun `filter by multiple DependsOn task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocker3 = repo.addTask(spaceId, title = "Blocker 3")!!
        val dependent1 = repo.addTask(spaceId, title = "Dependent 1")!!
        val dependent2 = repo.addTask(spaceId, title = "Dependent 2")!!
        val dependent3 = repo.addTask(spaceId, title = "Dependent 3")!!

        repo.addConnection(dependent1.id, blocker1.id, ConnectionType.DependsOn)
        repo.addConnection(dependent2.id, blocker2.id, ConnectionType.DependsOn)
        repo.addConnection(dependent3.id, blocker3.id, ConnectionType.DependsOn)

        val criteria = TaskFilterCriteria(dependsOnTaskIds = "${blocker1.id}, ${blocker2.id}")
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(2, results.size)
        assertTrue(results.none { it.task.title.contains("3") })
    }

    @Test
    fun `filter by specific SubtaskOf task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent1 = repo.addTask(spaceId, title = "Parent 1")!!
        val parent2 = repo.addTask(spaceId, title = "Parent 2")!!
        val child1 = repo.addTask(spaceId, title = "Child 1")!!
        val child2 = repo.addTask(spaceId, title = "Child 2")!!
        repo.addTask(spaceId, title = "Standalone")

        repo.addConnection(child1.id, parent1.id, ConnectionType.SubtaskOf)
        repo.addConnection(child2.id, parent2.id, ConnectionType.SubtaskOf)

        val criteria = TaskFilterCriteria(subtaskOfTaskIds = parent1.id)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Child 1", results.first().task.title)
    }

    @Test
    fun `filter by specific RelatesTo task IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val related1 = repo.addTask(spaceId, title = "Related 1")!!
        val related2 = repo.addTask(spaceId, title = "Related 2")!!
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        repo.addTask(spaceId, title = "Unrelated")

        repo.addConnection(task1.id, related1.id, ConnectionType.RelatesTo)
        repo.addConnection(task2.id, related2.id, ConnectionType.RelatesTo)

        val criteria = TaskFilterCriteria(relatesToTaskIds = related1.id)
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Task 1", results.first().task.title)
    }

    @Test
    fun `connection ID filters are case insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val dependent = repo.addTask(spaceId, title = "Dependent")!!

        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        // Use lowercase ID in filter
        val criteria = TaskFilterCriteria(dependsOnTaskIds = blocker.id.lowercase())
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Dependent", results.first().task.title)
    }

    // ==================== Combined Filters Tests ====================

    @Test
    fun `multiple filters are combined with AND`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Match both", priority = Priority.HIGH)
        repo.addTask(spaceId, title = "Open only", priority = Priority.LOW)
        repo.addTask(spaceId, title = "High only", status = TaskStatus.Done, priority = Priority.HIGH)

        val criteria = TaskFilterCriteria(
            statusFilters = persistentSetOf(TaskStatus.Open),
            priorityFilter = PriorityFilter.High
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Match both", results.first().task.title)
    }

    @Test
    fun `text search and status filter combined`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Important open")
        repo.addTask(spaceId, title = "Important done", status = TaskStatus.Done)
        repo.addTask(spaceId, title = "Regular open")

        val criteria = TaskFilterCriteria(
            searchQuery = "important",
            textSearchFields = persistentSetOf(TaskTextSearchField.Title),
            statusFilters = persistentSetOf(TaskStatus.Open)
        )
        val results = repo.getAllWithTotalsFiltered(spaceId, criteria)

        assertEquals(1, results.size)
        assertEquals("Important open", results.first().task.title)
    }

    // ==================== Filter Enum Display Names Tests ====================

    @Test
    fun `TaskTextSearchField displayNames`() {
        assertEquals("ID", TaskTextSearchField.Id.displayName)
        assertEquals("Title", TaskTextSearchField.Title.displayName)
        assertEquals("Tags", TaskTextSearchField.Tags.displayName)
        assertEquals("Description", TaskTextSearchField.Description.displayName)
    }

    @Test
    fun `DueDateFilter displayNames`() {
        assertEquals("Any", DueDateFilter.Any.displayName)
        assertEquals("Overdue", DueDateFilter.Overdue.displayName)
        assertEquals("Today", DueDateFilter.Today.displayName)
        assertEquals("This Week", DueDateFilter.ThisWeek.displayName)
        assertEquals("This Month", DueDateFilter.ThisMonth.displayName)
        assertEquals("No Due Time", DueDateFilter.NoDueDate.displayName)
        assertEquals("Custom", DueDateFilter.Custom.displayName)
    }

    @Test
    fun `PriorityFilter displayNames`() {
        assertEquals("Any", PriorityFilter.Any.displayName)
        assertEquals("High (75-100)", PriorityFilter.High.displayName)
        assertEquals("Medium (50-74)", PriorityFilter.Medium.displayName)
        assertEquals("Low (1-49)", PriorityFilter.Low.displayName)
        assertEquals("No Priority", PriorityFilter.NoPriority.displayName)
        assertEquals("Custom", PriorityFilter.Custom.displayName)
    }

    @Test
    fun `EstimatedTimeFilter displayNames`() {
        assertEquals("Any", EstimatedTimeFilter.Any.displayName)
        assertEquals("No Estimate", EstimatedTimeFilter.NoEstimate.displayName)
        assertEquals("< 15 min", EstimatedTimeFilter.Quick.displayName)
        assertEquals("15-30 min", EstimatedTimeFilter.Short.displayName)
        assertEquals("30 min - 1 hr", EstimatedTimeFilter.Medium.displayName)
        assertEquals("1-4 hrs", EstimatedTimeFilter.Long.displayName)
        assertEquals("> 4 hrs", EstimatedTimeFilter.VeryLong.displayName)
        assertEquals("Custom", EstimatedTimeFilter.Custom.displayName)
    }

    @Test
    fun `TagMatchMode displayNames`() {
        assertEquals("Match any", TagMatchMode.Any.displayName)
        assertEquals("Match all", TagMatchMode.All.displayName)
    }

    @Test
    fun `RecurrenceFilter displayNames`() {
        assertEquals("Any", RecurrenceFilter.Any.displayName)
        assertEquals("No Recurrence", RecurrenceFilter.NoRecurrence.displayName)
        assertEquals("Has Recurrence", RecurrenceFilter.HasRecurrence.displayName)
    }

    @Test
    fun `NotificationsFilter displayNames`() {
        assertEquals("Any", NotificationsFilter.Any.displayName)
        assertEquals("No Notifications", NotificationsFilter.NoNotifications.displayName)
        assertEquals("Has Notifications", NotificationsFilter.HasNotifications.displayName)
    }

    @Test
    fun `AutoUpdateStatusFilter displayNames`() {
        assertEquals("Any", AutoUpdateStatusFilter.Any.displayName)
        assertEquals("Auto", AutoUpdateStatusFilter.Auto.displayName)
        assertEquals("Manual", AutoUpdateStatusFilter.Manual.displayName)
    }

    @Test
    fun `ConnectionTypeOption displayNames`() {
        assertEquals("Has dependencies", ConnectionTypeOption.DependsOn.displayName)
        assertEquals("Has dependents", ConnectionTypeOption.IsDependencyOf.displayName)
        assertEquals("Has related", ConnectionTypeOption.RelatesTo.displayName)
        assertEquals("Is subtask", ConnectionTypeOption.SubtaskOf.displayName)
        assertEquals("Has subtasks", ConnectionTypeOption.ParentOf.displayName)
        assertEquals("Is not subtask", ConnectionTypeOption.NotSubtask.displayName)
    }

    // ==================== Space Filtering Tests ====================

    @Test
    fun `filterSpaces by name`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Work Tasks", "WORK")
        repo.createSpace("Personal", "PERS")
        repo.createSpace("Shopping List", "SHOP")

        val spaces = repo.getAllTasks()
        val results = repo.filterSpaces("ork", searchInName = true, searchInPrefix = false)

        assertEquals(1, results.size)
        assertEquals("Work Tasks", results.first().name)
    }

    @Test
    fun `filterSpaces by prefix`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Work Tasks", "WORK")
        repo.createSpace("Personal", "PERS")
        repo.createSpace("Shopping List", "SHOP")

        val spaces = repo.getAllTasks()
        val results = repo.filterSpaces("PER", searchInName = false, searchInPrefix = true)

        assertEquals(1, results.size)
        assertEquals("Personal", results.first().name)
    }

    @Test
    fun `filterSpaces case insensitive`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Work Tasks", "WORK")

        val spaces = repo.getAllTasks()
        val results = repo.filterSpaces("work", searchInName = true, searchInPrefix = true)

        assertEquals(1, results.size)
    }

    @Test
    fun `filterSpaces with blank query returns all`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Space 1", "ONE")
        repo.createSpace("Space 2", "TWO")

        val spaces = repo.getAllTasks()
        val results = repo.filterSpaces("   ", searchInName = true, searchInPrefix = true)

        assertEquals(2, results.size)
    }
}
