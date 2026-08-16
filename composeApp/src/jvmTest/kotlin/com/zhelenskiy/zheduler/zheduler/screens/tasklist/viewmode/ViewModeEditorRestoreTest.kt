package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.runtime.saveable.SaverScope
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.ViewMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An activity recreation — a rotation, a theme switch — must not cost the user the view mode they
 * are part way through arranging. The editor used to be rebuilt from whatever was stored, throwing
 * away every level and rule added since, without the confirmation the back arrow gives.
 */
class ViewModeEditorRestoreTest {

    private val saver = ViewModeEditorState.saver(spaceId = "space-1", isCopy = false)

    /** [state] put through save and restore, as the platform would on recreation. */
    private fun recreate(state: ViewModeEditorState): ViewModeEditorState {
        val saved = with(saver) { SaverScope { true }.save(state) }
        return assertNotNull(saver.restore(assertNotNull(saved)), "nothing was restored")
    }

    @Test
    fun `an unsaved new mode survives recreation`() {
        val state = ViewModeEditorState(spaceId = "space-1")
        state.name = "By status then tags"
        state.groupingLevels.add(GroupingLevelState())
        state.groupingLevels.add(GroupingLevelState().apply {
            field = GroupableField.Tags
            initializeDefaultGroups()
        })

        val restored = recreate(state)

        assertEquals("By status then tags", restored.name)
        assertEquals(2, restored.groupingLevels.size, "the levels arranged before the rotation")
        assertEquals(GroupableField.Tags, restored.groupingLevels[1].field)
    }

    @Test
    fun `the id it would save under does not change`() {
        val state = ViewModeEditorState(spaceId = "space-1")
        state.name = "Mine"

        assertEquals(state.toViewMode().id, recreate(state).toViewMode().id, "a rotation forked the id")
    }

    @Test
    fun `an edit is still unsaved work after recreation`() {
        val stored = ViewMode(id = "vm-1", name = "Stored", spaceId = "space-1")
        val state = ViewModeEditorState(spaceId = "space-1")
        state.startFrom(stored)
        state.name = "Renamed"

        val restored = recreate(state)

        assertEquals("Renamed", restored.name)
        assertTrue(restored.hasChanges(), "leaving must still ask before discarding the rename")
    }

    @Test
    fun `an untouched editor has nothing to discard after recreation`() {
        val stored = ViewMode(id = "vm-1", name = "Stored", spaceId = "space-1")
        val state = ViewModeEditorState(spaceId = "space-1")
        state.startFrom(stored)

        assertFalse(recreate(state).hasChanges(), "nothing was edited, so leaving must not ask")
    }
}
