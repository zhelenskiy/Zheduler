package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
    val state = rememberRichTextState()
    val linkColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(linkColor) {
        state.config.linkColor = linkColor
    }

    // Get all space prefixes to build a dynamic regex pattern
    val taskIdPattern = remember(allSpacePrefixes) {
        if (allSpacePrefixes.isNotEmpty()) {
            val prefixes = allSpacePrefixes.joinToString("|")
            Regex("\\b($prefixes)-\\d+\\b")
        } else {
            Regex("\\b[A-Z]+-\\d+\\b")
        }
    }

    LaunchedEffect(markdown, taskIdPattern) {
        // Replace task ID references with markdown links
        val processedMarkdown = if (onTaskClick != null) {
            markdown.replace(taskIdPattern) { matchResult ->
                val taskId = matchResult.value
                "[$taskId]($taskId)"
            }
        } else {
            markdown
        }
            .replace("```", "") // the library does not support code blocks
        state.setMarkdown(processedMarkdown)
    }

    val defaultUriHandler = LocalUriHandler.current
    val customUriHandler = remember(onTaskClick, getTaskById, taskIdPattern, defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (onTaskClick != null && uri.matches(taskIdPattern)) {
                    // Only navigate if the task exists
                    if (getTaskById?.invoke(uri) != null) {
                        onTaskClick(uri)
                    }
                } else {
                    // Open regular URIs in the default browser
                    defaultUriHandler.openUri(uri)
                }
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
        RichText(
            state = state,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
