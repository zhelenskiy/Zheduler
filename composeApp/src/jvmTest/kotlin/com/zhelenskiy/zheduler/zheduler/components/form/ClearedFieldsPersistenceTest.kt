@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Restoring a form after process death has to put back what the user left, including the things
 * they took away. A cleared due date and an emptied tag set look, field by field, exactly like a
 * form that was never stored — so the record says whether it exists.
 */
class ClearedFieldsPersistenceTest {

    private fun stateWith(dueDate: Instant?, tags: Set<String>) = PersistedFormState(
        title = "Edited",
        description = "",
        priority = "",
        estimatedTime = "",
        tags = tags.let { persistentSetOf<String>().addAll(it) },
        dueDate = dueDate,
        status = TaskStatus.Open,
        connections = persistentSetOf(),
        connectionsBase = persistentSetOf(),
        notifications = persistentListOf(),
        recurrenceRules = persistentListOf(),
        autoUpdateStatusFromSubtasks = false,
    )

    /**
     * The same two cases, but through the write path the app actually uses.
     *
     * The records built by hand above leave `editedFields` null, which is how a record written by
     * an older build is read — applied whole. Every record `persistedIn` writes names the fields
     * the user touched, and it is that list a cleared field has to appear in.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a due date cleared on screen is recorded as an edit`() = runComposeUiTest {
        val handle = SavedStateHandle()
        val task = Task(
            id = "TASK-1",
            title = "Report",
            spaceId = "space-1",
            dueDate = Instant.fromEpochMilliseconds(1_700_000_000_000),
            tags = persistentSetOf("work"),
        )

        lateinit var form: TaskFormState
        setContent {
            form = rememberTaskFormState(task)
            form.persistedIn(FormStatePersistence(handle))
        }
        waitForIdle()

        form.dueDate = null
        form.tags = persistentSetOf()
        waitForIdle()

        // What the screen would be rebuilt from: the task, unchanged, as it still is in the database.
        val rebuilt = taskFormState(
            initialDueDate = task.dueDate,
            initialTags = task.tags,
        )
        assertNotNull(FormStatePersistence(handle).read(), "nothing was written").applyTo(rebuilt)

        assertNull(rebuilt.dueDate, "the due date the user removed came back")
        assertTrue(rebuilt.tags.isEmpty(), "the tags the user removed came back as ${rebuilt.tags}")
    }

    @Test
    fun `nothing stored reads as nothing`() {
        assertNull(FormStatePersistence(SavedStateHandle()).read())
    }

    @Test
    fun `a cleared due date stays cleared`() {
        val handle = SavedStateHandle()
        val persistence = FormStatePersistence(handle)
        persistence.write(stateWith(dueDate = null, tags = setOf("work")))

        val form = taskFormState(
            initialDueDate = Instant.fromEpochMilliseconds(1_700_000_000_000),
            initialTags = persistentSetOf("work"),
        )
        persistence.read()!!.applyTo(form)

        assertNull(form.dueDate, "the user removed the due date before the app was killed")
    }

    @Test
    fun `cleared tags stay cleared`() {
        val handle = SavedStateHandle()
        val persistence = FormStatePersistence(handle)
        persistence.write(stateWith(dueDate = null, tags = emptySet()))

        val form = taskFormState(initialTags = persistentSetOf("work", "urgent"))
        persistence.read()!!.applyTo(form)

        assertTrue(form.tags.isEmpty(), "the user removed every tag; they came back as ${form.tags}")
    }

    @Test
    fun `a prefilled form with nothing stored keeps its prefill`() {
        val persistence = FormStatePersistence(SavedStateHandle())
        val form = taskFormState(initialTags = persistentSetOf("work"))

        persistence.read()?.applyTo(form)

        assertEquals(persistentSetOf("work"), form.tags)
    }

    @Test
    fun `clearing the record makes it absent again`() {
        val handle = SavedStateHandle()
        val persistence = FormStatePersistence(handle)
        persistence.write(stateWith(dueDate = null, tags = emptySet()))
        persistence.clear()

        assertNull(persistence.read())
    }
}
