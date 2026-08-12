package com.zhelenskiy.zheduler.zheduler.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor is chosen per task. Hand-editing one task's table in Markdown should not change
 * how every other task is edited, nor how they are rendered on the detail screen.
 */
class EditorSettingsStateTest {

    @Test
    fun aChoiceAppliesOnlyToItsOwnTask() {
        val state = EditorSettingsState()
        state.setDescriptionEditorFor("ZH-1", DescriptionEditorKind.Markdown)

        assertEquals(DescriptionEditorKind.Markdown, state.descriptionEditorFor("ZH-1"))
        assertEquals(DescriptionEditorKind.Rich, state.descriptionEditorFor("ZH-2"))
    }

    @Test
    fun tasksWithoutAChoiceUseTheDefault() {
        val state = EditorSettingsState()

        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor("ZH-9"))
        // A task that does not exist yet has nothing to look up.
        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor(null))
    }

    /** Only differences are stored, so the map does not grow with every task ever opened. */
    @Test
    fun choosingTheDefaultDropsTheEntry() {
        val state = EditorSettingsState()
        state.setDescriptionEditorFor("ZH-1", DescriptionEditorKind.Markdown)
        assertTrue(state.descriptionEditorByTask.containsKey("ZH-1"))

        state.setDescriptionEditorFor("ZH-1", DefaultDescriptionEditor)

        assertEquals(emptyMap(), state.descriptionEditorByTask)
        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor("ZH-1"))
    }

    /** Storage is the state's own business: no screen has to forward or save it. */
    @Test
    fun aChoiceIsPersistedByTheStateItself() {
        val written = mutableListOf<Map<String, DescriptionEditorKind>>()
        val state = EditorSettingsState(onPersist = { written += it })

        state.setDescriptionEditorFor("ZH-1", DescriptionEditorKind.Markdown)

        assertEquals(listOf(mapOf("ZH-1" to DescriptionEditorKind.Markdown)), written)
    }

    /** Reading storage back must not immediately write it out again. */
    @Test
    fun restoringDoesNotPersist() {
        val written = mutableListOf<Map<String, DescriptionEditorKind>>()
        val state = EditorSettingsState(onPersist = { written += it })

        state.restore(mapOf("ZH-1" to DescriptionEditorKind.Markdown))

        assertEquals(emptyList(), written)
        assertEquals(DescriptionEditorKind.Markdown, state.descriptionEditorFor("ZH-1"))
    }

    /** Re-picking what is already in effect is not a change worth a write. */
    @Test
    fun anUnchangedChoiceIsNotPersisted() {
        val written = mutableListOf<Map<String, DescriptionEditorKind>>()
        val state = EditorSettingsState(onPersist = { written += it })

        state.setDescriptionEditorFor("ZH-1", DefaultDescriptionEditor)

        assertEquals(emptyList(), written)
    }

    @Test
    fun aTaskWithoutAnIdIsNotRemembered() {
        val state = EditorSettingsState()

        state.setDescriptionEditorFor(null, DescriptionEditorKind.Markdown)

        assertEquals(emptyMap(), state.descriptionEditorByTask)
    }
}
