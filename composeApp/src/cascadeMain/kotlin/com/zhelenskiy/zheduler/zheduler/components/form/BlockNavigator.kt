package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linreal.cascade.editor.action.FocusBlock
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.EditorStateHolder

/**
 * Arrow-key movement between blocks.
 *
 * The editor has none of its own: `FocusNextBlock` is dispatched only as a backspace/delete
 * fallback, so vertical arrows never leave the block they start in. That is invisible between
 * paragraphs — the caret simply stops — but it strands a divider or a table, which cannot take
 * a caret at all and so can never be stepped over.
 *
 * Movement only happens at a block's very first or last offset, so an arrow that still has
 * somewhere to go inside the current text is never stolen.
 */
@Stable
internal class BlockNavigator(
    private val stateHolder: EditorStateHolder,
    private val textStates: BlockTextStates,
    private val deleteBlock: (BlockId) -> Unit = {},
) {
    /**
     * True while a table cell owns the keyboard. Those cells are our own text fields, unknown
     * to the editor's focus state, so the editor-level handler must keep its hands off.
     */
    var cellHasFocus by mutableStateOf(false)

    /** Handles an arrow key. Returns true when focus moved and the key should be consumed. */
    fun onArrow(direction: Int): Boolean {
        if (cellHasFocus) return false
        val focusedId = stateHolder.state.focusedBlockId ?: return false
        return isAtEdgeOfText(focusedId, direction) && focusTextBlockBeside(focusedId, direction)
    }

    /**
     * Backspace at the very start of a block removes the block before it when that block
     * holds no text.
     *
     * The editor's own handler only merges into a block that supports text, so a divider or
     * table in front of the caret simply swallows the keystroke. Deleting it is what every
     * other editor does, and here it is also the only keyboard route: the block cannot take
     * a caret of its own to be deleted from.
     */
    fun onBackspace(): Boolean {
        if (cellHasFocus) return false
        val focusedId = stateHolder.state.focusedBlockId ?: return false
        val blocks = stateHolder.state.blocks
        val index = blocks.indexOfFirst { it.id == focusedId }
        if (index <= 0) return false

        val selection = textStates.getSelection(focusedId) ?: return false
        if (!selection.collapsed || selection.start != 0) return false

        val previous = blocks[index - 1]
        if (previous.type.supportsText) return false
        deleteBlock(previous.id)
        return true
    }

    /**
     * Moves the caret to the nearest text block on [direction], skipping blocks that cannot
     * hold one. Returns false at the ends of the document, leaving the caret where it is.
     */
    fun focusTextBlockBeside(fromBlockId: BlockId, direction: Int): Boolean {
        val blocks = stateHolder.state.blocks
        val index = blocks.indexOfFirst { it.id == fromBlockId }
        if (index < 0) return false

        val candidates = if (direction > 0) (index + 1)..blocks.lastIndex else (index - 1) downTo 0
        val targetIndex = candidates.firstOrNull { blocks[it].type.supportsText } ?: return false
        val target = blocks[targetIndex]

        stateHolder.dispatch(FocusBlock(target.id))
        val text = textStates.getVisibleText(target.id).orEmpty()
        textStates.setCursorPosition(target.id, if (direction > 0) 0 else text.length)
        return true
    }

    /**
     * Whether the caret sits at the boundary an arrow would cross. A non-text block is always
     * at its edge: there is nowhere inside it for the caret to be.
     */
    private fun isAtEdgeOfText(blockId: BlockId, direction: Int): Boolean {
        val block = stateHolder.state.blocks.firstOrNull { it.id == blockId } ?: return false
        if (!block.type.supportsText) return true

        val selection = textStates.getSelection(blockId) ?: return false
        if (!selection.collapsed) return false
        val text = textStates.getVisibleText(blockId).orEmpty()
        return if (direction > 0) selection.end >= text.length else selection.start <= 0
    }
}
