package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter panel is composed before the stored criteria have been read, and every save comes
 * back through the store as a new value. Both halves of that loop can destroy the user's filter.
 */
class PersistedFilterStateTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `nothing is saved before the stored criteria arrive`() = runComposeUiTest {
        var stored by mutableStateOf<TaskFilterCriteria?>(null)
        val saves = mutableListOf<TaskFilterCriteria>()

        setContent {
            rememberPersistedFilterState(
                onLoadFilterState = { stored },
                onSaveFilterState = { saves += it },
            )
        }
        waitForIdle()

        assertTrue(
            saves.isEmpty(),
            "saving the panel's defaults here overwrites the stored filter: $saves",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the stored criteria are applied when they arrive`() = runComposeUiTest {
        var stored by mutableStateOf<TaskFilterCriteria?>(null)
        lateinit var filterState: TaskFilterState

        setContent {
            filterState = rememberPersistedFilterState(
                onLoadFilterState = { stored },
                onSaveFilterState = {},
            )
        }
        waitForIdle()

        stored = TaskFilterCriteria(searchQuery = "urgent")
        waitForIdle()

        assertEquals("urgent", filterState.searchQuery)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an echo of an earlier save does not revert newer input`() = runComposeUiTest {
        var stored by mutableStateOf<TaskFilterCriteria?>(null)
        lateinit var filterState: TaskFilterState

        setContent {
            filterState = rememberPersistedFilterState(
                onLoadFilterState = { stored },
                onSaveFilterState = { stored = it },
            )
        }
        waitForIdle()

        stored = TaskFilterCriteria()
        waitForIdle()

        // The user types faster than the store round-trips: "u" is saved, then the rest arrives
        // before that save echoes back.
        filterState.searchQuery = "u"
        filterState.searchQuery = "urgent"
        waitForIdle()

        // The store now republishes the lagging "u" snapshot.
        stored = TaskFilterCriteria(searchQuery = "u")
        waitForIdle()

        assertEquals("urgent", filterState.searchQuery, "a stale echo must not reach the panel")
    }
}
