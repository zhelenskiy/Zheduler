package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingValidationError
import com.zhelenskiy.zheduler.zheduler.GroupingValidationResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The editor decides whether Save is enabled from this. A range typed as text is only visible here:
 * once a GroupDefinition exists, "10 to 2" and an unparseable bound are both just nulls.
 */
class ViewModeEditorValidationTest {

    private fun editorWithPriorityGroup(
        label: String = "Group",
        min: String,
        max: String,
    ): ViewModeEditorState {
        val state = ViewModeEditorState(spaceId = "space-0-TEST")
        state.name = "Mode"
        val level = GroupingLevelState().also(state.groupingLevels::add)
        level.field = GroupableField.Priority
        level.groups.clear()
        level.addGroup()
        level.groups.single().apply {
            this.label = label
            priorityMinText = min
            priorityMaxText = max
        }
        return state
    }

    @Test
    fun `an inverted priority range is rejected`() {
        val result = editorWithPriorityGroup(min = "80", max = "20").validate()

        val invalid = assertIs<GroupingValidationResult.Invalid>(result, "min above max should not be saveable")
        assertTrue(invalid.errors.any { it is GroupingValidationError.InvalidRange }, "errors were ${invalid.errors}")
    }

    @Test
    fun `a priority range that is not a number is rejected`() {
        val result = editorWithPriorityGroup(min = "abc", max = "").validate()

        assertIs<GroupingValidationResult.Invalid>(result, "an unparseable bound should not be saveable")
    }

    @Test
    fun `a blank group label is rejected`() {
        val result = editorWithPriorityGroup(label = "  ", min = "1", max = "10").validate()

        val invalid = assertIs<GroupingValidationResult.Invalid>(result)
        assertTrue(invalid.errors.any { it is GroupingValidationError.EmptyGroupLabel }, "errors were ${invalid.errors}")
    }

    @Test
    fun `a well formed range is accepted`() {
        val result = editorWithPriorityGroup(min = "20", max = "80").validate()

        assertIs<GroupingValidationResult.Valid>(result)
    }
}
