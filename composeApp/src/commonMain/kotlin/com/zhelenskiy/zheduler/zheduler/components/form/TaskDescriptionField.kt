package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.settings.DescriptionEditorKind
import com.zhelenskiy.zheduler.zheduler.settings.LocalEditorSettings

/**
 * The task description field, with the editor the user picked.
 *
 * Both editors read and write the same Markdown [markdown] string, so switching between
 * them mid-edit keeps whatever has been typed. The choice belongs to [taskId] rather than to
 * the app: dropping to Markdown to hand-edit one task's table leaves every other task alone.
 * Where only one editor exists — JS, which has no cascade-editor artifact — no choice is
 * offered.
 *
 * [preview] renders the description as it will appear once saved. It is shown alongside
 * Markdown source and omitted for the WYSIWYG editor, which is already a preview.
 */
@Composable
fun TaskDescriptionField(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    label: String,
    taskId: String?,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val editorSettings = LocalEditorSettings.current
    // Held here as well as persisted, so a task that has no id yet can still be switched.
    var chosen by remember(taskId) {
        mutableStateOf(editorSettings.descriptionEditorFor(taskId))
    }
    val kind = if (isRichTaskDescriptionEditorAvailable) {
        chosen
    } else {
        DescriptionEditorKind.Markdown
    }

    // Owned by the field, not by either editor, so a step survives switching between them.
    val history = remember { DescriptionHistoryState(markdown) }
    val currentOnMarkdownChange by rememberUpdatedState(onMarkdownChange)
    LaunchedEffect(markdown) { history.record(markdown) }

    val restore: (String) -> Unit = { restored -> currentOnMarkdownChange(restored) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Previewed above both editors: each has an undo of its own that would otherwise
            // take the keystroke and diverge from the shared history behind the buttons.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val shortcut = event.isMetaPressed || event.isCtrlPressed
                when {
                    !shortcut -> false
                    // Consumed either way: a null result means the mounted editor's own history
                    // handled the step, and letting the event through would run the field's
                    // built-in undo on top of it — two steps for one keystroke.
                    event.key == Key.Z && event.isShiftPressed -> { history.redo()?.let(restore); true }
                    event.key == Key.Z -> { history.undo()?.let(restore); true }
                    // Windows and Linux redo.
                    event.key == Key.Y -> { history.redo()?.let(restore); true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UndoRedoControls(history = history, onRestore = restore)
            if (isRichTaskDescriptionEditorAvailable) {
                DescriptionEditorPicker(
                    selected = kind,
                    onSelect = { selected ->
                        chosen = selected
                        editorSettings.setDescriptionEditorFor(taskId, selected)
                    },
                )
            }
        }

        when (kind) {
            DescriptionEditorKind.Rich -> TaskDescriptionEditor(
                markdown = markdown,
                onMarkdownChange = onMarkdownChange,
                label = label,
                preview = preview,
                history = history,
            )

            DescriptionEditorKind.Markdown -> {
                RawMarkdownField(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    label = label,
                    history = history,
                )
                preview()
            }
        }
    }
}

@Composable
private fun DescriptionEditorPicker(
    selected: DescriptionEditorKind,
    onSelect: (DescriptionEditorKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kinds = DescriptionEditorKind.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                label = { Text(kind.label) },
            )
        }
    }
}
