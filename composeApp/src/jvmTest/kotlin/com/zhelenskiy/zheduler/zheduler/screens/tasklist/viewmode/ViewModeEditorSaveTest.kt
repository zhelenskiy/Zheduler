package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingValidationError
import com.zhelenskiy.zheduler.zheduler.GroupingValidationResult
import com.zhelenskiy.zheduler.zheduler.OrderDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the editor lets the user save, and what it counts as work worth keeping.
 */
class ViewModeEditorSaveTest {

    private fun editor() = ViewModeEditorState(spaceId = "space-1")

    @Test
    fun `a level with no groups is rejected`() {
        val state = editor()
        state.groupingLevels.add(GroupingLevelState())
        // Tags is the field that starts with no groups of its own.
        state.groupingLevels.single().field = GroupableField.Tags
        state.groupingLevels.single().initializeDefaultGroups()

        val result = assertIs<GroupingValidationResult.Invalid>(
            state.validate(),
            "a level that groups nothing puts every task in one unnamed bucket",
        )
        assertTrue(result.errors.any { it is GroupingValidationError.EmptyLevel }, "got ${result.errors}")
    }

    @Test
    fun `a level with groups is accepted`() {
        val state = editor()
        state.groupingLevels.add(GroupingLevelState())
        assertIs<GroupingValidationResult.Valid>(state.validate(), "Status starts with default groups")
    }

    @Test
    fun `the id a new mode saves under does not change between calls`() {
        val state = editor()
        state.name = "My Mode"

        assertEquals(
            state.toViewMode().id,
            state.toViewMode().id,
            "a second tap on Save would otherwise create a second view mode",
        )
    }

    @Test
    fun `an existing mode keeps its id`() {
        val original = ViewModeEditorState(spaceId = "space-1").apply { name = "Original" }.toViewMode()
        val state = ViewModeEditorState(original, "space-1")

        assertEquals(original.id, state.toViewMode().id)
    }

    @Test
    fun `editing only the ordering counts as an unsaved change`() {
        val state = editor()
        assertFalse(state.hasChanges(), "an untouched new mode has nothing to discard")

        state.defaultOrderingRules.first().direction = when (state.defaultOrderingRules.first().direction) {
            OrderDirection.Ascending -> OrderDirection.Descending
            OrderDirection.Descending -> OrderDirection.Ascending
        }

        assertTrue(state.hasChanges(), "back-press would otherwise discard this without asking")
    }
}
