package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.components.form.isRichTaskDescriptionEditorAvailable
import com.zhelenskiy.zheduler.zheduler.settings.DescriptionEditorKind
import com.zhelenskiy.zheduler.zheduler.settings.LocalEditorSettings

/**
 * Read-only description, rendered by whichever editor the user writes with.
 *
 * Editing in the block editor and then reading a Markdown rendering means two sets of type
 * scales, list bullets and quote styling for the same text; this keeps what you see after
 * saving identical to what you saw while typing — per task, matching whichever editor that
 * task is edited with.
 */
@Composable
fun TaskDescriptionView(
    markdown: String,
    taskId: String?,
    modifier: Modifier = Modifier,
    allSpacePrefixes: List<String> = emptyList(),
    getTaskById: ((String) -> Task?)? = null,
    onTaskClick: ((String) -> Unit)? = null,
) {
    val editorSettings = LocalEditorSettings.current
    val useRich = isRichTaskDescriptionEditorAvailable &&
        editorSettings.descriptionEditorFor(taskId) == DescriptionEditorKind.Rich

    if (useRich) {
        RichMarkdownText(
            markdown = markdown,
            modifier = modifier,
            allSpacePrefixes = allSpacePrefixes,
            getTaskById = getTaskById,
            onTaskClick = onTaskClick,
        )
    } else {
        SimpleMarkdownText(
            markdown = markdown,
            modifier = modifier,
            allSpacePrefixes = allSpacePrefixes,
            getTaskById = getTaskById,
            onTaskClick = onTaskClick,
        )
    }
}

/**
 * Renders [markdown] with the block editor's own renderers. Falls back to
 * [SimpleMarkdownText] where cascade-editor does not ship (JS).
 */
@Composable
expect fun RichMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    allSpacePrefixes: List<String> = emptyList(),
    getTaskById: ((String) -> Task?)? = null,
    onTaskClick: ((String) -> Unit)? = null,
)
