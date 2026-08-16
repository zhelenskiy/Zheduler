@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
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
        notifications = persistentListOf(),
        recurrenceRules = persistentListOf(),
        autoUpdateStatusFromSubtasks = false,
    )

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
