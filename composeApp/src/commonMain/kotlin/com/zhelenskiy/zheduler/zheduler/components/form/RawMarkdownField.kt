@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Plain Markdown text area.
 *
 * Used where the block editor is unavailable (JS) or unsafe — a description the Markdown
 * codec cannot round-trip is edited as raw source rather than silently rewritten.
 *
 * Backed by a [rememberTextFieldState] rather than a plain string so the field keeps an undo
 * stack of its own, which is published to [history] as its finer-grained layer.
 */
@Composable
internal fun RawMarkdownField(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    history: DescriptionHistoryState? = null,
) {
    val state = rememberTextFieldState(markdown)
    val currentOnMarkdownChange by rememberUpdatedState(onMarkdownChange)

    // Guards the two directions against each other: without it, echoing a value back in
    // would look like a fresh edit and typing would fight the incoming value.
    var lastSynced by remember { mutableStateOf(markdown) }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { edited ->
            if (edited != lastSynced) {
                lastSynced = edited
                currentOnMarkdownChange(edited)
            }
        }
    }

    LaunchedEffect(markdown) {
        if (markdown != lastSynced) {
            lastSynced = markdown
            state.setTextAndPlaceCursorAtEnd(markdown)
        }
    }


    if (history != null) {
        val undoState = state.undoState
        DisposableEffect(history, undoState) {
            history.editor = object : EditorHistory {
                override val canUndo: Boolean get() = undoState.canUndo
                override val canRedo: Boolean get() = undoState.canRedo
                override fun undo() = undoState.undo()
                override fun redo() = undoState.redo()
            }
            onDispose { history.editor = null }
        }
    }

    OutlinedTextField(
        state = state,
        label = { Text(label) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5),
    )
}
