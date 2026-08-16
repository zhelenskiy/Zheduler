package com.zhelenskiy.zheduler.zheduler.screens.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.components.form.FormStatePersistence
import com.zhelenskiy.zheduler.zheduler.components.form.PersistedFormState
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormState
import com.zhelenskiy.zheduler.zheduler.components.form.persistedIn
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The new-task form is composed before the container has read anything, so everything it starts
 * from — the connection to prefill, the task being copied — arrives one state emission late.
 */
class NewTaskFormPrefillTest {

    private val prefilled = TaskConnection("TASK-1", ConnectionType.DependsOn)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theConnectionSurvivesArrivingAfterTheFormIsBuilt() = runComposeUiTest {
        var connections by mutableStateOf<PersistentSet<TaskConnection>>(persistentSetOf())
        lateinit var formState: TaskFormState

        setContent {
            formState = rememberFormStateFromData(taskToCopy = null, initialConnections = connections)
        }

        waitForIdle()
        assertTrue(formState.connections.isEmpty(), "nothing has loaded yet")

        connections = persistentSetOf(prefilled)
        waitForIdle()

        assertEquals(persistentSetOf(prefilled), formState.connections)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEditedFormIsNotRebuiltWhenTheSameDataIsRepublished() = runComposeUiTest {
        var connections by mutableStateOf<PersistentSet<TaskConnection>>(persistentSetOf(prefilled))
        lateinit var formState: TaskFormState

        setContent {
            formState = rememberFormStateFromData(taskToCopy = null, initialConnections = connections)
        }

        waitForIdle()
        formState.title = "Half-written"

        // The container re-runs its load on every re-subscribe and emits an equal value.
        connections = persistentSetOf(prefilled)
        waitForIdle()

        assertEquals("Half-written", formState.title, "an equal reload must not discard the input")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun typedInputIsRestoredAfterProcessDeath() = runComposeUiTest {
        val handle = SavedStateHandle()

        lateinit var form: TaskFormState
        setContent {
            form = rememberFormStateFromData(null, persistentSetOf())
            form.persistedIn(FormStatePersistence(handle))
        }
        waitForIdle()
        form.title = "Draft"
        form.priority = "42"
        waitForIdle()

        val restored = FormStatePersistence(handle).read()
        assertEquals("Draft", restored.title)
        assertEquals("42", restored.priority)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theEmptyRebuiltFormDoesNotOverwriteWhatWasAlreadySaved() = runComposeUiTest {
        val handle = SavedStateHandle()
        FormStatePersistence(handle).write(
            PersistedFormState(
                title = "Saved before death",
                description = null,
                priority = null,
                estimatedTime = null,
                tags = persistentSetOf(),
                dueDate = null,
            )
        )

        var connections by mutableStateOf<PersistentSet<TaskConnection>>(persistentSetOf())
        lateinit var formState: TaskFormState
        setContent {
            formState = rememberFormStateFromData(null, connections)
            formState.persistedIn(FormStatePersistence(handle))
        }
        waitForIdle()

        // The prefill lands, the form is rebuilt empty, and the restore has to win the race.
        connections = persistentSetOf(prefilled)
        waitForIdle()

        assertEquals("Saved before death", formState.title)
        assertEquals("Saved before death", FormStatePersistence(handle).read().title)
    }
}
