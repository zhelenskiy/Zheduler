@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Clock

class InMemoryIsMissedRepositoryTest : IsMissedRepositoryTest(), InMemoryRepositoryTest
class DatabaseIsMissedRepositoryTest : IsMissedRepositoryTest(), DatabaseRepositoryTest

/**
 * Integration tests for isMissed with repository's totalDueDate calculation.
 * These tests verify that isMissed correctly uses the total due date from dependencies.
 */
abstract class IsMissedRepositoryTest : AbstractRepositoryTest {

    @Test
    fun `task with no due date is not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "No due date task")!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertFalse(taskWithTotals.isMissed(Clock.System.now()))
    }

    @Test
    fun `task with future due date is not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val futureDue = Clock.System.now() + 7.days
        val task = repo.addTask(spaceId, title = "Future task", dueDate = futureDue)!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertFalse(taskWithTotals.isMissed(Clock.System.now()))
    }

    @Test
    fun `task with past due date and Open status is missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val pastDue = Clock.System.now() - 1.days
        val task = repo.addTask(spaceId, title = "Overdue task", dueDate = pastDue)!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertTrue(taskWithTotals.isMissed(Clock.System.now()))
    }

    @Test
    fun `task with past due date and Done status is not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val pastDue = Clock.System.now() - 1.days
        val task = repo.addTask(spaceId, title = "Completed task", status = TaskStatus.Done, dueDate = pastDue)!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertFalse(taskWithTotals.isMissed(Clock.System.now()))
    }

    @Test
    fun `task with past due date and Declined status is not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val pastDue = Clock.System.now() - 1.days
        val task = repo.addTask(
            spaceId,
            title = "Declined task",
            status = TaskStatus.Declined("reason"),
            dueDate = pastDue
        )!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertFalse(taskWithTotals.isMissed(Clock.System.now()))
    }

    @Test
    fun `blocker task inherits missed status from dependent with past due date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create a dependent task with past due date
        val dependent = repo.addTask(spaceId, title = "Dependent with deadline", dueDate = pastDue)!!
        
        // Create a blocker task with no due date, but dependent depends on it
        val blocker = repo.addTask(spaceId, title = "Blocker task")!!
        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        
        // Blocker should have totalDueDate from the dependent (compare milliseconds to avoid precision issues)
        assertNotNull(blockerWithTotals.totalDueDate)
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate!!.toEpochMilliseconds())
        
        // Blocker is missed because its totalDueDate (from dependent) is in the past
        assertTrue(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `blocker task not missed when dependent has future due date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val futureDue = now + 7.days

        // Create a dependent task with future due date
        val dependent = repo.addTask(spaceId, title = "Dependent with deadline", dueDate = futureDue)!!
        
        // Create a blocker task with no due date
        val blocker = repo.addTask(spaceId, title = "Blocker task")!!
        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        
        // Blocker should have totalDueDate from the dependent (compare milliseconds to avoid precision issues)
        assertEquals(futureDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // Blocker is not missed because its totalDueDate is in the future
        assertFalse(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `blocker task uses closest due date from multiple dependents`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        val futureDue = now + 7.days

        // Create dependent tasks with different due dates
        val dependent1 = repo.addTask(spaceId, title = "Dependent 1", dueDate = futureDue)!!
        val dependent2 = repo.addTask(spaceId, title = "Dependent 2", dueDate = pastDue)!!
        
        // Create a blocker task that both dependents depend on
        val blocker = repo.addTask(spaceId, title = "Blocker task")!!
        repo.addConnection(dependent1.id, blocker.id, ConnectionType.DependsOn)
        repo.addConnection(dependent2.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        
        // Blocker should have the closest (earliest) due date (compare milliseconds to avoid precision issues)
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // Blocker is missed because the closest totalDueDate is in the past
        assertTrue(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `task blocked by another task inherits missed status via Blocked status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create a task that will be the blocker (with past due date)
        val blockerTask = repo.addTask(spaceId, title = "Blocker")!!
        
        // Create a blocked task with past due date
        val blockedTask = repo.addTask(
            spaceId,
            title = "Blocked task",
            status = TaskStatus.Blocked(persistentSetOf(blockerTask.id)),
            dueDate = pastDue
        )!!

        val blockedWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blockedTask.id }!!
        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blockerTask.id }!!
        
        // Blocked task is missed (past due date, not Done/Declined)
        assertTrue(blockedWithTotals.isMissed(now))
        
        // Blocker should also have totalDueDate from the blocked task (compare milliseconds to avoid precision issues)
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `completed blocker task is not missed even with past total due date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create a dependent task with past due date
        val dependent = repo.addTask(spaceId, title = "Dependent", dueDate = pastDue)!!
        
        // Create a blocker task that is already done
        val blocker = repo.addTask(spaceId, title = "Blocker", status = TaskStatus.Done)!!
        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        
        // Blocker has totalDueDate from dependent (compare milliseconds to avoid precision issues)
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // But blocker is not missed because it's Done
        assertFalse(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `task with own future due date but past total due date from dependency is missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        val futureDue = now + 7.days

        // Create a dependent task with past due date
        val dependent = repo.addTask(spaceId, title = "Dependent", dueDate = pastDue)!!
        
        // Create a blocker task with future due date
        val blocker = repo.addTask(spaceId, title = "Blocker", dueDate = futureDue)!!
        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        
        // Total due date should be the closest (past due from dependent) - compare milliseconds to avoid precision issues
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // Task's own due date is in future, but totalDueDate is past
        assertFalse(blocker.isMissed(now)) // Task's own isMissed
        assertTrue(blockerWithTotals.isMissed(now)) // TaskWithTotals isMissed uses totalDueDate
    }

    @Test
    fun `chain of dependencies propagates missed status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create a chain: A depends on B depends on C
        // Only A has a due date (past)
        val taskA = repo.addTask(spaceId, title = "Task A", dueDate = pastDue)!!
        val taskB = repo.addTask(spaceId, title = "Task B")!!
        val taskC = repo.addTask(spaceId, title = "Task C")!!
        
        repo.addConnection(taskA.id, taskB.id, ConnectionType.DependsOn)
        repo.addConnection(taskB.id, taskC.id, ConnectionType.DependsOn)

        val tasksWithTotals = repo.getAllTasksWithTotals(spaceId)
        val aWithTotals = tasksWithTotals.find { it.task.id == taskA.id }!!
        val bWithTotals = tasksWithTotals.find { it.task.id == taskB.id }!!
        val cWithTotals = tasksWithTotals.find { it.task.id == taskC.id }!!
        
        // All tasks should have the same totalDueDate from A (compare milliseconds to avoid precision issues)
        val pastDueMillis = pastDue.toEpochMilliseconds()
        assertEquals(pastDueMillis, aWithTotals.totalDueDate?.toEpochMilliseconds())
        assertEquals(pastDueMillis, bWithTotals.totalDueDate?.toEpochMilliseconds())
        assertEquals(pastDueMillis, cWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // All tasks should be missed
        assertTrue(aWithTotals.isMissed(now))
        assertTrue(bWithTotals.isMissed(now))
        assertTrue(cWithTotals.isMissed(now))
    }

    // ==================== InProgress and Blocked status tests ====================

    @Test
    fun `task with past due date and InProgress status is missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        val task = repo.addTask(
            spaceId,
            title = "InProgress overdue task",
            status = TaskStatus.InProgress,
            dueDate = pastDue
        )!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `task with past due date and Blocked status is missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        
        val blocker = repo.addTask(spaceId, title = "Blocker task")!!
        val task = repo.addTask(
            spaceId,
            title = "Blocked overdue task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id)),
            dueDate = pastDue
        )!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `task with past due date and Blocked status with comment is missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        
        val task = repo.addTask(
            spaceId,
            title = "Blocked overdue task",
            status = TaskStatus.Blocked(persistentSetOf(), "Waiting for external input"),
            dueDate = pastDue
        )!!

        val taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        
        assertTrue(taskWithTotals.isMissed(now))
    }

    // ==================== Parent/Subtask tests ====================

    @Test
    fun `parent task inherits totalDueDate from subtask with earlier due date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        val futureDue = now + 7.days

        // Create parent with future due date
        val parent = repo.addTask(spaceId, title = "Parent task", dueDate = futureDue)!!
        
        // Create subtask with past due date
        val subtask = repo.addTask(spaceId, title = "Subtask", dueDate = pastDue)!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        val parentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == parent.id }!!
        
        // Parent's own due date is in future, so it's not missed based on own dueDate
        assertFalse(parent.isMissed(now))
        
        // But parent doesn't inherit subtask's due date (subtasks don't affect parent's totalDueDate)
        // totalDueDate only propagates via DependsOn/IsDependencyOf and Blocked status
        assertEquals(futureDue.toEpochMilliseconds(), parentWithTotals.totalDueDate?.toEpochMilliseconds())
        assertFalse(parentWithTotals.isMissed(now))
    }

    @Test
    fun `subtask with no due date but parent has past due date - subtask not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with past due date
        val parent = repo.addTask(spaceId, title = "Parent task", dueDate = pastDue)!!
        
        // Create subtask with no due date
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        val subtaskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == subtask.id }!!
        
        // Subtask doesn't inherit parent's due date via SubtaskOf
        // (totalDueDate only propagates via DependsOn/IsDependencyOf and Blocked status)
        assertNull(subtaskWithTotals.totalDueDate)
        assertFalse(subtaskWithTotals.isMissed(now))
    }

    @Test
    fun `subtask that is also a dependency inherits missed status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with past due date
        val parent = repo.addTask(spaceId, title = "Parent task", dueDate = pastDue)!!
        
        // Create subtask that parent also depends on
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(parent.id, subtask.id, ConnectionType.DependsOn)

        val subtaskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == subtask.id }!!
        
        // Subtask inherits totalDueDate via DependsOn (not SubtaskOf)
        assertEquals(pastDue.toEpochMilliseconds(), subtaskWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(subtaskWithTotals.isMissed(now))
    }

    @Test
    fun `completed subtask not missed even when parent has past due date and depends on it`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with past due date
        val parent = repo.addTask(spaceId, title = "Parent task", dueDate = pastDue)!!
        
        // Create completed subtask that parent depends on
        val subtask = repo.addTask(spaceId, title = "Subtask", status = TaskStatus.Done)!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(parent.id, subtask.id, ConnectionType.DependsOn)

        val subtaskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == subtask.id }!!
        
        // Subtask has totalDueDate from parent
        assertEquals(pastDue.toEpochMilliseconds(), subtaskWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // But subtask is not missed because it's Done
        assertFalse(subtaskWithTotals.isMissed(now))
    }

    @Test
    fun `nested subtasks - grandchild inherits missed status via dependency chain`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create hierarchy: grandparent -> parent -> child
        val grandparent = repo.addTask(spaceId, title = "Grandparent", dueDate = pastDue)!!
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child = repo.addTask(spaceId, title = "Child")!!
        
        // Set up subtask relationships
        repo.addConnection(parent.id, grandparent.id, ConnectionType.SubtaskOf)
        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)
        
        // Also set up dependency chain so due dates propagate
        repo.addConnection(grandparent.id, parent.id, ConnectionType.DependsOn)
        repo.addConnection(parent.id, child.id, ConnectionType.DependsOn)

        val tasksWithTotals = repo.getAllTasksWithTotals(spaceId)
        val grandparentWithTotals = tasksWithTotals.find { it.task.id == grandparent.id }!!
        val parentWithTotals = tasksWithTotals.find { it.task.id == parent.id }!!
        val childWithTotals = tasksWithTotals.find { it.task.id == child.id }!!
        
        // All should have the same totalDueDate
        val pastDueMillis = pastDue.toEpochMilliseconds()
        assertEquals(pastDueMillis, grandparentWithTotals.totalDueDate?.toEpochMilliseconds())
        assertEquals(pastDueMillis, parentWithTotals.totalDueDate?.toEpochMilliseconds())
        assertEquals(pastDueMillis, childWithTotals.totalDueDate?.toEpochMilliseconds())
        
        // All should be missed
        assertTrue(grandparentWithTotals.isMissed(now))
        assertTrue(parentWithTotals.isMissed(now))
        assertTrue(childWithTotals.isMissed(now))
    }

    // ==================== Automation/Status change tests ====================

    @Test
    fun `task becomes not missed after status changes to Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        
        val task = repo.addTask(spaceId, title = "Overdue task", dueDate = pastDue)!!
        
        // Initially missed
        var taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        assertTrue(taskWithTotals.isMissed(now))
        
        // Mark as Done
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Done))
        
        // No longer missed
        taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `task becomes not missed after status changes to Declined`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days
        
        val task = repo.addTask(spaceId, title = "Overdue task", dueDate = pastDue)!!
        
        // Initially missed
        var taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        assertTrue(taskWithTotals.isMissed(now))
        
        // Mark as Declined
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Declined("No longer needed")))
        
        // No longer missed
        taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `blocker task becomes not missed after completing - dependent still missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create dependent with past due date
        val dependent = repo.addTask(spaceId, title = "Dependent", dueDate = pastDue)!!
        
        // Create blocker
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)
        
        // Both initially missed
        var blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        var dependentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependent.id }!!
        assertTrue(blockerWithTotals.isMissed(now))
        assertTrue(dependentWithTotals.isMissed(now))
        
        // Complete blocker
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        
        // Blocker no longer missed, but dependent still is
        blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        dependentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependent.id }!!
        assertFalse(blockerWithTotals.isMissed(now))
        assertTrue(dependentWithTotals.isMissed(now))
    }

    @Test
    fun `task unblocked automatically still missed if past due`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create blocker
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        
        // Create blocked task with past due date
        val blockedTask = repo.addTask(
            spaceId,
            title = "Blocked task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id)),
            dueDate = pastDue
        )!!
        
        // Initially missed (Blocked but past due)
        var taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blockedTask.id }!!
        assertTrue(taskWithTotals.isMissed(now))
        
        // Complete blocker - this should automatically unblock the task (change to InProgress)
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        
        // Task is now InProgress (unblocked) but still missed because past due
        taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blockedTask.id }!!
        assertEquals(TaskStatus.InProgress, taskWithTotals.task.status)
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `dependency inherits totalDueDate from dependent task and becomes missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create dependent task with past due date
        val dependent = repo.addTask(
            spaceId,
            title = "Dependent",
            dueDate = pastDue
        )!!

        // Create dependency (blocker) with no due date
        val dependency = repo.addTask(spaceId, title = "Dependency")!!

        // Dependency initially not missed (no due date, no connections)
        var dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertNull(dependencyWithTotals.totalDueDate)
        assertFalse(dependencyWithTotals.isMissed(now))

        // Add dependency relationship: dependent DependsOn dependency
        // This means dependency must be completed before dependent's deadline
        repo.addConnection(dependent.id, dependency.id, ConnectionType.DependsOn)

        // Dependency now has totalDueDate from dependent (via IsDependencyOf) and is missed
        dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), dependencyWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(dependencyWithTotals.isMissed(now))
    }

    @Test
    fun `completing all subtasks makes parent Done and not missed`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with autoUpdateStatusFromSubtasks
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            dueDate = pastDue,
            autoUpdateStatusFromSubtasks = true
        )!!
        
        // Create subtasks
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)
        
        // Trigger auto-update by updating subtask status
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Open))
        
        // Parent is Open and missed
        var parentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == parent.id }!!
        assertEquals(TaskStatus.Open, parentWithTotals.task.status)
        assertTrue(parentWithTotals.isMissed(now))
        
        // Complete both subtasks
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))
        
        // Parent is now Done (auto-updated) and not missed
        parentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == parent.id }!!
        assertEquals(TaskStatus.Done, parentWithTotals.task.status)
        assertFalse(parentWithTotals.isMissed(now))
    }

    @Test
    fun `isMissed changes based on time passing`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val soonDue = now + 1.hours

        val task = repo.addTask(spaceId, title = "Task due soon", dueDate = soonDue)!!

        // Not missed yet
        var taskWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == task.id }!!
        assertFalse(taskWithTotals.isMissed(now))

        // Simulate time passing - check with a later time
        val laterTime = now + 2.hours
        assertTrue(taskWithTotals.isMissed(laterTime))
    }

    // ==================== ParentOf connection tests ====================

    @Test
    fun `ParentOf connection does not propagate totalDueDate from child to parent`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with no due date
        val parent = repo.addTask(spaceId, title = "Parent")!!

        // Create child with past due date
        val child = repo.addTask(spaceId, title = "Child", dueDate = pastDue)!!

        // Connect using ParentOf (parent is parent of child)
        repo.addConnection(parent.id, child.id, ConnectionType.ParentOf)

        // Parent should NOT inherit child's due date via ParentOf
        val parentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == parent.id }!!
        assertNull(parentWithTotals.totalDueDate)
        assertFalse(parentWithTotals.isMissed(now))

        // Child should keep its own due date
        val childWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == child.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), childWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(childWithTotals.isMissed(now))
    }

    @Test
    fun `SubtaskOf connection does not propagate totalDueDate from parent to child`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create parent with past due date
        val parent = repo.addTask(spaceId, title = "Parent", dueDate = pastDue)!!

        // Create child with no due date
        val child = repo.addTask(spaceId, title = "Child")!!

        // Connect using SubtaskOf (child is subtask of parent)
        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        // Child should NOT inherit parent's due date via SubtaskOf
        val childWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == child.id }!!
        assertNull(childWithTotals.totalDueDate)
        assertFalse(childWithTotals.isMissed(now))

        // Parent should keep its own due date and be missed
        val parentWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == parent.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), parentWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(parentWithTotals.isMissed(now))
    }

    // ==================== Automation/totalDueDate update tests ====================

    @Test
    fun `adding DependsOn connection updates totalDueDate for dependency`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create two independent tasks
        val taskWithDeadline = repo.addTask(spaceId, title = "Task with deadline", dueDate = pastDue)!!
        val dependency = repo.addTask(spaceId, title = "Dependency")!!

        // Initially dependency has no totalDueDate
        var dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertNull(dependencyWithTotals.totalDueDate)
        assertFalse(dependencyWithTotals.isMissed(now))

        // Add dependency connection
        repo.addConnection(taskWithDeadline.id, dependency.id, ConnectionType.DependsOn)

        // Now dependency should have totalDueDate from the task that depends on it
        dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), dependencyWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(dependencyWithTotals.isMissed(now))
    }

    @Test
    fun `removing DependsOn connection updates totalDueDate for dependency`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create connected tasks
        val taskWithDeadline = repo.addTask(spaceId, title = "Task with deadline", dueDate = pastDue)!!
        val dependency = repo.addTask(spaceId, title = "Dependency")!!
        repo.addConnection(taskWithDeadline.id, dependency.id, ConnectionType.DependsOn)

        // Initially dependency is missed
        var dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertTrue(dependencyWithTotals.isMissed(now))

        // Remove dependency connection
        repo.removeConnection(taskWithDeadline.id, dependency.id, ConnectionType.DependsOn)

        // Now dependency should NOT have totalDueDate anymore
        dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertNull(dependencyWithTotals.totalDueDate)
        assertFalse(dependencyWithTotals.isMissed(now))
    }

    @Test
    fun `updating task due date updates totalDueDate for dependencies`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val futureDue = now + 7.days
        val pastDue = now - 1.days

        // Create connected tasks with future due date
        val taskWithDeadline = repo.addTask(spaceId, title = "Task with deadline", dueDate = futureDue)!!
        val dependency = repo.addTask(spaceId, title = "Dependency")!!
        repo.addConnection(taskWithDeadline.id, dependency.id, ConnectionType.DependsOn)

        // Initially dependency is NOT missed (future due date)
        var dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertEquals(futureDue.toEpochMilliseconds(), dependencyWithTotals.totalDueDate?.toEpochMilliseconds())
        assertFalse(dependencyWithTotals.isMissed(now))

        // Fetch the current task state (includes connections added via addConnection)
        val currentTask = repo.getTaskById(taskWithDeadline.id)!!

        // Update task's due date to past
        repo.updateTask(currentTask.copy(dueDate = pastDue))

        // Now dependency should be missed
        dependencyWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == dependency.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), dependencyWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(dependencyWithTotals.isMissed(now))
    }

    @Test
    fun `Blocked status propagates totalDueDate to blocker tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create blocker task
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create blocked task with past due date
        val blockedTask = repo.addTask(
            spaceId,
            title = "Blocked task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id)),
            dueDate = pastDue
        )!!

        // Blocker should have totalDueDate from blocked task
        val blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `changing task status to Blocked propagates totalDueDate to new blockers`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create independent blocker task
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create task with past due date (not blocked yet)
        val task = repo.addTask(spaceId, title = "Task", dueDate = pastDue)!!

        // Initially blocker has no totalDueDate
        var blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        assertNull(blockerWithTotals.totalDueDate)
        assertFalse(blockerWithTotals.isMissed(now))

        // Change task status to Blocked
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))

        // Now blocker should have totalDueDate from blocked task
        blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        assertEquals(pastDue.toEpochMilliseconds(), blockerWithTotals.totalDueDate?.toEpochMilliseconds())
        assertTrue(blockerWithTotals.isMissed(now))
    }

    @Test
    fun `changing task status from Blocked removes totalDueDate propagation from old blockers`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val pastDue = now - 1.days

        // Create blocker and blocked task
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val blockedTask = repo.addTask(
            spaceId,
            title = "Blocked task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id)),
            dueDate = pastDue
        )!!

        // Initially blocker is missed
        var blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        assertTrue(blockerWithTotals.isMissed(now))

        // Unblock the task (change to Open)
        repo.updateTask(repo.getTaskById(blockedTask.id)!!.copy(status = TaskStatus.Open))

        // Now blocker should NOT have totalDueDate from the previously blocked task
        blockerWithTotals = repo.getAllTasksWithTotals(spaceId).find { it.task.id == blocker.id }!!
        assertNull(blockerWithTotals.totalDueDate)
        assertFalse(blockerWithTotals.isMissed(now))
    }
}
