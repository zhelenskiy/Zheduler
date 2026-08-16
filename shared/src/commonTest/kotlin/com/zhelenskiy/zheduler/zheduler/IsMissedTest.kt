@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf

import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class IsMissedTest {

    private val now = Instant.fromEpochMilliseconds(1700000000000) // Fixed point in time for tests
    private val pastDue = now - 1.days
    private val futureDue = now + 1.days

    // ==================== Task.isMissed Tests ====================

    @Test
    fun `Task isMissed returns false when no due date`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = null)
        assertFalse(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns false when due date is in the future`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = futureDue)
        assertFalse(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns true when due date is in the past and status is Open`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Open)
        assertTrue(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns true when due date is in the past and status is InProgress`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.InProgress)
        assertTrue(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns true when due date is in the past and status is Blocked`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Blocked(persistentSetOf()))
        assertTrue(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns false when due date is in the past but status is Done`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Done)
        assertFalse(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns false when due date is in the past but status is Declined`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Declined("reason"))
        assertFalse(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns false when due date equals current time`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = now, status = TaskStatus.Open)
        assertFalse(task.isMissed(now))
    }

    @Test
    fun `Task isMissed returns true when due date is 1 millisecond in the past`() {
        // Actually a millisecond, which is the point: the sibling test pins `dueDate == now` to
        // false, so the cutoff is load-bearing and an hour's margin never exercised it.
        val task = Task(
            id = "TEST-1",
            title = "Test",
            spaceId = "space-1",
            dueDate = now - 1.milliseconds,
            status = TaskStatus.Open,
        )
        assertTrue(task.isMissed(now))
    }

    // ==================== TaskWithTotals.isMissed Tests ====================

    @Test
    fun `TaskWithTotals isMissed returns false when no total due date`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = null, totalPriority = null)
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns false when total due date is in the future`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = futureDue, totalPriority = null)
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns true when total due date is in the past`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = futureDue, status = TaskStatus.Open)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed uses totalDueDate not task dueDate`() {
        // Task's own due date is in the future, but totalDueDate (from dependencies) is in the past
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = futureDue, status = TaskStatus.Open)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        
        // Task itself is not missed based on its own due date
        assertFalse(task.isMissed(now))
        // But TaskWithTotals is missed based on totalDueDate
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns false when total due date is past but status is Done`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Done)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns false when total due date is past but status is Declined`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Declined("reason"))
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        assertFalse(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns true when total due date is past and status is Blocked`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = futureDue, status = TaskStatus.Blocked(persistentSetOf("BLOCKER-1")))
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        assertTrue(taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals isMissed returns true when total due date is past and status is InProgress`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = futureDue, status = TaskStatus.InProgress)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        assertTrue(taskWithTotals.isMissed(now))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `TaskWithTotals with same task and total due date behaves consistently with Task`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = pastDue, status = TaskStatus.Open)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        
        assertEquals(task.isMissed(now), taskWithTotals.isMissed(now))
    }

    @Test
    fun `TaskWithTotals with null task due date but non-null total due date in past is missed`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1", dueDate = null, status = TaskStatus.Open)
        val taskWithTotals = TaskWithTotals(task, totalDueDate = pastDue, totalPriority = null)
        
        // Task is not missed (no due date)
        assertFalse(task.isMissed(now))
        // But TaskWithTotals is missed (has total due date from dependencies)
        assertTrue(taskWithTotals.isMissed(now))
    }
}
