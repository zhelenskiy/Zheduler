package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.zhelenskiy.zheduler.zheduler.Task

@Composable
fun SimpleMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    allSpacePrefixes: List<String> = emptyList(),
    getTaskById: ((String) -> Task?)? = null,
    onTaskClick: ((String) -> Unit)? = null
) {
    val taskIdPattern = remember(allSpacePrefixes) { taskIdPattern(allSpacePrefixes) }

    // Code blocks and tables are drawn here rather than by the rich-text library, which
    // supports neither.
    val segments = remember(markdown) { parseMarkdownSegments(markdown) }

    val defaultUriHandler = LocalUriHandler.current
    val customUriHandler = remember(onTaskClick, getTaskById, taskIdPattern, defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.matches(taskIdPattern)) {
                    // Navigate on the reference alone. The caller's task map holds only
                    // connected tasks, so a miss there means "not loaded here", not
                    // "does not exist" — gating on it silently swallowed the click. A
                    // reference is never handed to the browser: it is not a URL.
                    onTaskClick?.invoke(uri)
                } else {
                    // Open regular URIs in the default browser.
                    defaultUriHandler.openUri(uri)
                }
            }
        }
    }

    val linkTaskIds = onTaskClick != null

    CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            segments.forEach { segment ->
                when (segment) {
                    is MarkdownSegment.Prose -> MarkdownProse(
                        markdown = segment.markdown,
                        taskIdPattern = taskIdPattern,
                        linkTaskIds = linkTaskIds,
                    )

                    is MarkdownSegment.Code -> MarkdownCodeBlock(segment)

                    is MarkdownSegment.Table -> MarkdownTable(
                        table = segment,
                        taskIdPattern = taskIdPattern,
                        linkTaskIds = linkTaskIds,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownProse(
    markdown: String,
    taskIdPattern: Regex,
    linkTaskIds: Boolean,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val state = rememberRichTextState()
    val linkColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(linkColor) {
        state.config.linkColor = linkColor
    }

    LaunchedEffect(markdown, taskIdPattern, linkTaskIds) {
        state.setMarkdown(
            if (linkTaskIds) linkifyTaskReferences(markdown, taskIdPattern) else markdown
        )
    }

    RichText(state = state, style = style)
}

@Composable
private fun MarkdownCodeBlock(code: MarkdownSegment.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (code.language != null) {
                Text(
                    text = code.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
            Text(
                text = code.code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                // Code is not reflowed: long lines scroll instead of wrapping mid-token.
                softWrap = false,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownSegment.Table,
    taskIdPattern: Regex,
    linkTaskIds: Boolean,
) {
    val shape = MaterialTheme.shapes.small
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column {
            MarkdownTableRow(
                cells = table.header,
                alignments = table.alignments,
                taskIdPattern = taskIdPattern,
                linkTaskIds = linkTaskIds,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            )
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MarkdownTableRow(
                    cells = row,
                    alignments = table.alignments,
                    taskIdPattern = taskIdPattern,
                    linkTaskIds = linkTaskIds,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    alignments: List<MarkdownColumnAlignment>,
    taskIdPattern: Regex,
    linkTaskIds: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, cell ->
            val alignment = when (alignments.getOrElse(index) { MarkdownColumnAlignment.Start }) {
                MarkdownColumnAlignment.Start -> Alignment.CenterStart
                MarkdownColumnAlignment.Center -> Alignment.Center
                MarkdownColumnAlignment.End -> Alignment.CenterEnd
            }
            Box(
                // Equal-width columns: cells wrap rather than the table scrolling, which
                // keeps wide tables usable on a phone.
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = alignment,
            ) {
                MarkdownProse(
                    markdown = cell,
                    taskIdPattern = taskIdPattern,
                    linkTaskIds = linkTaskIds,
                    style = style,
                )
            }
        }
    }
}
