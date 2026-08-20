@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import com.zhelenskiy.zheduler.zheduler.util.LocalNow
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * "Missed" has to appear when the deadline passes, not when the screen next happens to be redrawn.
 *
 * Reading the clock during composition looks like it works, because the badge is right whenever
 * anything causes a redraw — opening a task and coming back, say. Sitting on the list while the
 * deadline goes by, which is the case that matters, it never appeared at all.
 */
class MissedBadgeAppearsTest {

    private val due = Instant.parse("2026-06-01T09:00:00Z")

    @Test
    fun theBadgeAppearsWhenTheDeadlinePassesWithTheScreenUntouched() = runComposeUiTest {
        var now by mutableStateOf(due - 5.minutes)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNow provides now) {
                    TaskCard(
                        taskWithTotals = TaskWithTotals(
                            task = Task(id = "TEST-1", title = "Pay rent", dueDate = due, spaceId = "s"),
                            totalDueDate = due,
                            totalPriority = null,
                        ),
                        onClick = {},
                        onDelete = {},
                        onCopy = {},
                    )
                }
            }
        }
        waitForIdle()
        onAllNodesWithText("Missed").assertCountEquals(0)

        // Nothing about the task changes — only the time.
        now = due + 1.minutes
        waitForIdle()

        onAllNodesWithText("Missed").assertCountEquals(1)
    }

    @Test
    fun theDueDateTurnsOverdueWhenTheDeadlinePassesWithTheScreenUntouched() = runComposeUiTest {
        var now by mutableStateOf(due - 5.minutes)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNow provides now) {
                    DueDateBadge(dueDate = due, isTotal = false)
                }
            }
        }
        waitForIdle()
        onAllNodesWithContentDescription("Overdue").assertCountEquals(0)

        now = due + 1.minutes
        waitForIdle()

        // The same signal that turns the date red, and the one a screen reader gets.
        onAllNodesWithContentDescription("Overdue").assertCountEquals(1)
    }
}
