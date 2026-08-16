package com.zhelenskiy.zheduler.zheduler.components.form

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The snapshot layer of the description history, exercised on its own — no editor published, so
 * every step goes through the stack rather than being handed to the editor's own undo.
 *
 * Edits made within the coalescing window are one step, so each edit here is separated by a wait
 * the test cannot make; instead every edit is preceded by an undo/redo or spaced by a real pause.
 */
class SnapshotHistoryOrderTest {

    /** Records [value] as its own step rather than merging it into the previous one. */
    private fun DescriptionHistoryState.recordStep(value: String) {
        Thread.sleep(750)
        record(value)
    }

    @Test
    fun `undo walks back in the order the edits were made`() {
        val history = DescriptionHistoryState("one")
        history.recordStep("two")
        history.recordStep("three")

        assertEquals("two", history.undo())
        assertEquals("one", history.undo())
        assertNull(history.undo(), "there is nothing before the initial value")
        assertFalse(history.canUndo)
    }

    @Test
    fun `redo walks forward in the same order`() {
        val history = DescriptionHistoryState("one")
        history.recordStep("two")
        history.recordStep("three")

        history.undo()
        history.undo()

        assertEquals("two", history.redo())
        assertEquals("three", history.redo())
        assertNull(history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new edit after an undo drops the redos`() {
        val history = DescriptionHistoryState("one")
        history.recordStep("two")
        history.recordStep("three")

        assertEquals("two", history.undo())
        assertTrue(history.canRedo)

        history.recordStep("branch")
        assertFalse(history.canRedo, "the abandoned future is gone")
        assertEquals("two", history.undo())
    }

    @Test
    fun `edits inside the coalescing window are one step`() {
        val history = DescriptionHistoryState("")
        history.record("t")
        history.record("ty")
        history.record("typ")
        history.record("type")

        assertEquals("", history.undo(), "typing undoes as one step, not letter by letter")
        assertFalse(history.canUndo)
    }

    @Test
    fun `an edit the history itself produced is followed, not stacked`() {
        val history = DescriptionHistoryState("one")
        history.recordStep("two")

        val undone = history.undo()
        assertEquals("one", undone)
        // The editor reports the restored text back; the history must recognise its own step.
        history.record(undone!!)

        assertEquals("two", history.redo(), "the redo must still be there")
    }
}
