package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * cascade-editor publishes no JS artifact, so this target keeps the raw Markdown field.
 * Descriptions stay interchangeable with the other platforms because the stored format is
 * Markdown either way.
 */
actual val isRichTaskDescriptionEditorAvailable: Boolean = false

@Composable
actual fun TaskDescriptionEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    preview: @Composable () -> Unit,
    history: DescriptionHistoryState?,
) {
    RawMarkdownField(
        markdown = markdown,
        onMarkdownChange = onMarkdownChange,
        label = label,
        modifier = modifier,
        history = history,
    )
    preview()
}
