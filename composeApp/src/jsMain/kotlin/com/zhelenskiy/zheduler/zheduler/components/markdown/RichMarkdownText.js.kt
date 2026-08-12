package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhelenskiy.zheduler.zheduler.Task

/**
 * cascade-editor publishes no JS artifact, so this target renders Markdown directly. It is
 * also never reached: without the block editor there is no rich mode to select.
 */
@Composable
actual fun RichMarkdownText(
    markdown: String,
    modifier: Modifier,
    allSpacePrefixes: List<String>,
    getTaskById: ((String) -> Task?)?,
    onTaskClick: ((String) -> Unit)?,
) {
    SimpleMarkdownText(
        markdown = markdown,
        modifier = modifier,
        allSpacePrefixes = allSpacePrefixes,
        getTaskById = getTaskById,
        onTaskClick = onTaskClick,
    )
}
