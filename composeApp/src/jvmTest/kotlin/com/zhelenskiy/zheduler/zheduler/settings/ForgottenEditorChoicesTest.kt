package com.zhelenskiy.zheduler.zheduler.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task ids come back. They are handed out per space as `PREFIX-1`, `PREFIX-2`, and deleting a task
 * — or a whole space, prefix and all — puts those names back into circulation. A choice left
 * behind then belongs to nothing, and the next task given that id inherits it: opening in an
 * editor its owner never picked.
 */
class ForgottenEditorChoicesTest {

    private fun settings(vararg choices: Pair<String, DescriptionEditorKind>): EditorSettingsState {
        val state = EditorSettingsState()
        choices.forEach { (taskId, kind) -> state.setDescriptionEditorFor(taskId, kind) }
        return state
    }

    @Test
    fun `a deleted task's choice is not inherited by the id's next owner`() {
        val state = settings("WORK-3" to DescriptionEditorKind.Markdown)

        state.forget { it == "WORK-3" }

        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor("WORK-3"))
    }

    @Test
    fun `deleting a space forgets the choices of every task in it`() {
        val state = settings(
            "WORK-1" to DescriptionEditorKind.Markdown,
            "WORK-2" to DescriptionEditorKind.Markdown,
            "HOME-1" to DescriptionEditorKind.Markdown,
        )

        state.forget { it.startsWith("WORK-") }

        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor("WORK-1"))
        assertEquals(DefaultDescriptionEditor, state.descriptionEditorFor("WORK-2"))
        assertEquals(
            DescriptionEditorKind.Markdown,
            state.descriptionEditorFor("HOME-1"),
            "another space's tasks are not the ones being deleted",
        )
    }

    @Test
    fun `what is forgotten is written out, not merely dropped from memory`() {
        var persisted: Map<String, DescriptionEditorKind>? = null
        val state = EditorSettingsState(onPersist = { persisted = it })
        state.setDescriptionEditorFor("WORK-1", DescriptionEditorKind.Markdown)

        state.forget { it == "WORK-1" }

        assertEquals(emptyMap(), persisted, "the choice would come back on the next launch")
    }

    @Test
    fun `forgetting nothing writes nothing`() {
        var writes = 0
        val state = EditorSettingsState(onPersist = { writes++ })
        state.setDescriptionEditorFor("WORK-1", DescriptionEditorKind.Markdown)
        val before = writes

        state.forget { it == "HOME-9" }

        assertEquals(before, writes)
    }
}
