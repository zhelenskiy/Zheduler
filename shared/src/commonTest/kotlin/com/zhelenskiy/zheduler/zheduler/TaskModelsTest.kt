@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlin.test.*
import kotlin.time.Clock

class TaskModelsTest {

    // ==================== Priority Tests ====================

    @Test
    fun `Priority with value 1 is valid`() {
        val priority = Priority(1)
        assertEquals(1, priority.value)
    }

    @Test
    fun `Priority with value 100 is valid`() {
        val priority = Priority(100)
        assertEquals(100, priority.value)
    }

    @Test
    fun `Priority with value 50 is valid`() {
        val priority = Priority(50)
        assertEquals(50, priority.value)
    }

    @Test
    fun `Priority with value 0 throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Priority(0)
        }
    }

    @Test
    fun `Priority with value 101 throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Priority(101)
        }
    }

    @Test
    fun `Priority with negative value throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Priority(-1)
        }
    }

    @Test
    fun `Priority with large negative value throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Priority(-100)
        }
    }

    @Test
    fun `Priority comparison works correctly`() {
        val low = Priority(25)
        val medium = Priority(50)
        val high = Priority(75)

        assertTrue(low < medium)
        assertTrue(medium < high)
        assertTrue(low < high)
        assertTrue(high > low)
        assertEquals(0, Priority(50).compareTo(Priority(50)))
    }

    @Test
    fun `Priority constants have correct values`() {
        assertEquals(1, Priority.MIN.value)
        assertEquals(25, Priority.LOW.value)
        assertEquals(50, Priority.MEDIUM.value)
        assertEquals(75, Priority.HIGH.value)
        assertEquals(100, Priority.MAX.value)
    }

    @Test
    fun `Priority constants comparison`() {
        assertTrue(Priority.MIN < Priority.LOW)
        assertTrue(Priority.LOW < Priority.MEDIUM)
        assertTrue(Priority.MEDIUM < Priority.HIGH)
        assertTrue(Priority.HIGH < Priority.MAX)
    }

    // ==================== TaskStatus Tests ====================

    @Test
    fun `TaskStatus Open displayName`() {
        assertEquals("Open", TaskStatus.Open.displayName)
    }

    @Test
    fun `TaskStatus Blocked displayName`() {
        assertEquals("Blocked", TaskStatus.Blocked(emptySet()).displayName)
    }

    @Test
    fun `TaskStatus InProgress displayName`() {
        assertEquals("In Progress", TaskStatus.InProgress.displayName)
    }

    @Test
    fun `TaskStatus Done displayName`() {
        assertEquals("Done", TaskStatus.Done.displayName)
    }

    @Test
    fun `TaskStatus Declined displayName`() {
        assertEquals("Declined", TaskStatus.Declined("reason").displayName)
    }

    @Test
    fun `TaskStatus Blocked stores blocker IDs`() {
        val blockerIds = setOf("TASK-1", "TASK-2", "TASK-3")
        val blocked = TaskStatus.Blocked(blockerIds)
        assertEquals(blockerIds, blocked.blockerTaskIds)
    }

    @Test
    fun `TaskStatus Blocked with empty set is valid`() {
        val blocked = TaskStatus.Blocked(emptySet())
        assertTrue(blocked.blockerTaskIds.isEmpty())
    }

    @Test
    fun `TaskStatus Blocked with comment`() {
        val blocked = TaskStatus.Blocked(setOf("TASK-1"), "Waiting for approval")
        assertEquals("Waiting for approval", blocked.comment)
    }

    @Test
    fun `TaskStatus Declined stores reason`() {
        val declined = TaskStatus.Declined("No longer needed")
        assertEquals("No longer needed", declined.reason)
    }

    @Test
    fun `TaskStatus Declined with empty reason is valid`() {
        val declined = TaskStatus.Declined("")
        assertEquals("", declined.reason)
    }

    // ==================== ConnectionType Tests ====================

    @Test
    fun `ConnectionType RelatesTo displayName`() {
        assertEquals("Relates to", ConnectionType.RelatesTo.displayName)
    }

    @Test
    fun `ConnectionType DependsOn displayName`() {
        assertEquals("Depends on", ConnectionType.DependsOn.displayName)
    }

    @Test
    fun `ConnectionType IsDependencyOf displayName`() {
        assertEquals("Is dependency of", ConnectionType.IsDependencyOf.displayName)
    }

    @Test
    fun `ConnectionType SubtaskOf displayName`() {
        assertEquals("Is subtask of", ConnectionType.SubtaskOf.displayName)
    }

    @Test
    fun `ConnectionType ParentOf displayName`() {
        assertEquals("Is parent for", ConnectionType.ParentOf.displayName)
    }

    @Test
    fun `ConnectionType symmetric for RelatesTo is RelatesTo`() {
        assertEquals(ConnectionType.RelatesTo, ConnectionType.RelatesTo.symmetric)
    }

    @Test
    fun `ConnectionType symmetric for DependsOn is IsDependencyOf`() {
        assertEquals(ConnectionType.IsDependencyOf, ConnectionType.DependsOn.symmetric)
    }

    @Test
    fun `ConnectionType symmetric for IsDependencyOf is DependsOn`() {
        assertEquals(ConnectionType.DependsOn, ConnectionType.IsDependencyOf.symmetric)
    }

    @Test
    fun `ConnectionType symmetric for SubtaskOf is ParentOf`() {
        assertEquals(ConnectionType.ParentOf, ConnectionType.SubtaskOf.symmetric)
    }

    @Test
    fun `ConnectionType symmetric for ParentOf is SubtaskOf`() {
        assertEquals(ConnectionType.SubtaskOf, ConnectionType.ParentOf.symmetric)
    }

    @Test
    fun `ConnectionType symmetric is involutive`() {
        for (type in ConnectionType.entries) {
            assertEquals(type, type.symmetric.symmetric)
        }
    }

    // ==================== Space Tests ====================

    @Test
    fun `Space with valid uppercase prefix is created`() {
        val space = Space(id = "space-1", name = "Test Space", idPrefix = "TEST")
        assertEquals("TEST", space.idPrefix)
    }

    @Test
    fun `Space with single letter prefix is valid`() {
        val space = Space(id = "space-1", name = "Test", idPrefix = "A")
        assertEquals("A", space.idPrefix)
    }

    @Test
    fun `Space with long prefix is valid`() {
        val space = Space(id = "space-1", name = "Test", idPrefix = "VERYLONGPREFIX")
        assertEquals("VERYLONGPREFIX", space.idPrefix)
    }

    @Test
    fun `Space with lowercase prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "test")
        }
    }

    @Test
    fun `Space with mixed case prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "Test")
        }
    }

    @Test
    fun `Space with numbers in prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "TEST1")
        }
    }

    @Test
    fun `Space with special characters in prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "TEST-A")
        }
    }

    @Test
    fun `Space with underscore in prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "TEST_A")
        }
    }

    @Test
    fun `Space with empty prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "")
        }
    }

    @Test
    fun `Space with space in prefix throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            Space(id = "space-1", name = "Test", idPrefix = "TEST A")
        }
    }

    // ==================== Task Tests ====================

    @Test
    fun `Task isRecurring returns false for non-recurring task`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertFalse(task.isRecurring)
    }

    @Test
    fun `Task isRecurring returns true for recurring task`() {
        val task = Task(
            id = "TEST-1",
            title = "Test",
            spaceId = "space-1",
            recurrenceRule = RecurrenceRule.AfterInterval(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = Clock.System.now()
            )
        )
        assertTrue(task.isRecurring)
    }

    @Test
    fun `Task default status is Open`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertEquals(TaskStatus.Open, task.status)
    }

    @Test
    fun `Task default connections is empty`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertTrue(task.connections.isEmpty())
    }

    @Test
    fun `Task default tags is empty`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertTrue(task.tags.isEmpty())
    }

    @Test
    fun `Task default notifications is empty`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertTrue(task.notifications.isEmpty())
    }

    @Test
    fun `Task default autoUpdateStatusFromSubtasks is false`() {
        val task = Task(id = "TEST-1", title = "Test", spaceId = "space-1")
        assertFalse(task.autoUpdateStatusFromSubtasks)
    }

    // ==================== TaskConnection Tests ====================

    @Test
    fun `TaskConnection equality by targetTaskId and type`() {
        val conn1 = TaskConnection("TASK-1", ConnectionType.DependsOn)
        val conn2 = TaskConnection("TASK-1", ConnectionType.DependsOn)
        val conn3 = TaskConnection("TASK-1", ConnectionType.RelatesTo)
        val conn4 = TaskConnection("TASK-2", ConnectionType.DependsOn)

        assertEquals(conn1, conn2)
        assertNotEquals(conn1, conn3)
        assertNotEquals(conn1, conn4)
    }

    // ==================== TaskNotification Tests ====================

    @Test
    fun `TaskNotification stores period correctly`() {
        val period = RecurrencePeriod(days = 1, hours = 2)
        val notification = TaskNotification(period)
        assertEquals(period, notification.timeBeforeDeadline)
    }

    // ==================== StatusChange Tests ====================

    @Test
    fun `StatusChange stores all fields`() {
        val now = Clock.System.now()
        val change = StatusChange(
            timestamp = now,
            previousStatus = TaskStatus.Open,
            newStatus = TaskStatus.InProgress,
            automaticChangeReason = AutomaticChangeReason.Unblocked
        )

        assertEquals(now, change.timestamp)
        assertEquals(TaskStatus.Open, change.previousStatus)
        assertEquals(TaskStatus.InProgress, change.newStatus)
        assertEquals(AutomaticChangeReason.Unblocked, change.automaticChangeReason)
    }

    @Test
    fun `StatusChange previousStatus can be null for initial status`() {
        val change = StatusChange(
            timestamp = Clock.System.now(),
            previousStatus = null,
            newStatus = TaskStatus.Open
        )

        assertNull(change.previousStatus)
    }

    @Test
    fun `StatusChange default automaticChangeReason is null`() {
        val change = StatusChange(
            timestamp = Clock.System.now(),
            previousStatus = TaskStatus.Open,
            newStatus = TaskStatus.Done
        )

        assertNull(change.automaticChangeReason)
    }
}
