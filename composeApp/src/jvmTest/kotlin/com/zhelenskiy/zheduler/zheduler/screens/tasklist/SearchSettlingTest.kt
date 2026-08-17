@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.PriorityFilter
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every distinct set of criteria costs the list a fresh count of each group and a fresh ranking of
 * its candidates, and with a search term those cannot be answered from the indexes — the rows have
 * to be read and matched. So the typing waits for a pause, while everything else does not.
 */
class SearchSettlingTest {

    @Test
    fun theListIsNotRequeriedInTheMiddleOfAWord() = runComposeUiTest {
        val state = TaskFilterState()
        val queried = mutableListOf<TaskFilterCriteria>()

        setContent {
            val criteria = state.toCriteriaAfterTyping()
            if (queried.lastOrNull() != criteria) queried += criteria
        }
        waitForIdle()

        "report".forEach { character ->
            state.searchQuery += character
            waitForIdle()
        }

        assertEquals(
            listOf(""),
            queried.map { it.searchQuery },
            "the list was queried again for every character typed",
        )

        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        assertEquals(listOf("", "report"), queried.map { it.searchQuery }, "the typing never arrived")
    }

    @Test
    fun aQueryThatArrivesWholeIsNotWaitedFor() = runComposeUiTest {
        val state = TaskFilterState()
        var latest by mutableStateOf(TaskFilterCriteria())

        setContent { latest = state.toCriteriaAfterTyping() }
        waitForIdle()

        // What the stored filter does when the screen opens, or a saved filter when applied.
        state.searchQuery = "quarterly report"
        waitForIdle()

        assertEquals("quarterly report", latest.searchQuery, "a restored filter should not wait")
    }

    @Test
    fun clearingTheBoxTakesEffectAtOnce() = runComposeUiTest {
        val state = TaskFilterState()
        var latest by mutableStateOf(TaskFilterCriteria())

        setContent { latest = state.toCriteriaAfterTyping() }
        waitForIdle()
        state.searchQuery = "report"
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        // The X button empties the box in one go, and the full list should come back at once.
        state.searchQuery = ""
        waitForIdle()

        assertEquals("", latest.searchQuery)
    }

    @Test
    fun everyOtherFilterTakesEffectAtOnce() = runComposeUiTest {
        val state = TaskFilterState()
        var latest by mutableStateOf(TaskFilterCriteria())

        setContent { latest = state.toCriteriaAfterTyping() }
        waitForIdle()

        state.priorityFilter = PriorityFilter.High
        waitForIdle()

        assertEquals(PriorityFilter.High, latest.priorityFilter, "a chip should not wait for a pause")
    }
}
