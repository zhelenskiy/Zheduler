@file:OptIn(ExperimentalCascadeMarkdownApi::class, ExperimentalCascadePreviewApi::class)

package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import io.github.linreal.cascade.editor.ui.ExperimentalCascadePreviewApi
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.markdown.ExperimentalCascadeMarkdownApi
import io.github.linreal.cascade.editor.markdown.MarkdownProfile
import io.github.linreal.cascade.editor.markdown.MarkdownSchema
import io.github.linreal.cascade.editor.registry.BlockPreviewRenderer
import io.github.linreal.cascade.editor.registry.BlockPreviewScope
import io.github.linreal.cascade.editor.theme.CascadeEditorTheme
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreview
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreviewConfig
import io.github.linreal.cascade.editor.ui.createEditorRegistry

private const val PreservedTypeId = "md.preserved"
private const val PreservedHtmlTypeId = "md.preservedHtml"
private const val RawMarkdownKey = "rawMarkdown"

@Composable
actual fun RichMarkdownText(
    markdown: String,
    modifier: Modifier,
    allSpacePrefixes: List<String>,
    getTaskById: ((String) -> Task?)?,
    onTaskClick: ((String) -> Unit)?,
) {
    val taskIdPattern = remember(allSpacePrefixes) { taskIdPattern(allSpacePrefixes) }

    // Bare `ZH-12` is not a link to the codec, so it is turned into one before decoding —
    // the same rewrite the Markdown renderer does, which is what makes references clickable.
    val blocks = remember(markdown, taskIdPattern, onTaskClick) {
        val source = if (onTaskClick != null) {
            linkifyTaskReferences(markdown, taskIdPattern)
        } else {
            markdown
        }
        MarkdownSchema.decode(source, MarkdownProfile.Default)
    }

    if (blocks == null) {
        // Decode aborted (a limit, or malformed input): show something rather than nothing.
        SimpleMarkdownText(
            markdown = markdown,
            modifier = modifier,
            allSpacePrefixes = allSpacePrefixes,
            getTaskById = getTaskById,
            onTaskClick = onTaskClick,
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    val theme = remember(scheme.surface) {
        val base = if (scheme.surface.luminance() < 0.5f) {
            CascadeEditorTheme.dark()
        } else {
            CascadeEditorTheme.light()
        }
        // The editor insets every block so its chrome has somewhere to sit. Read-only text
        // needs none of that, and it reads as a stray indent beside the rest of the screen.
        base.copy(dimensions = base.dimensions.copy(blockHorizontalPadding = 0.dp))
    }
    // Preview mode does not fall back to editor renderers, so preserved blocks would show as
    // "unsupported" boxes. Their source is Markdown, so the Markdown renderer draws them —
    // which is also how tables and code blocks get their real rendering here.
    val registry = remember(allSpacePrefixes, getTaskById, onTaskClick) {
        createEditorRegistry().apply {
            val renderer = PreservedMarkdownPreviewRenderer(
                allSpacePrefixes = allSpacePrefixes,
                getTaskById = getTaskById,
                onTaskClick = onTaskClick,
            )
            registerPreviewRenderer(PreservedTypeId, renderer)
            registerPreviewRenderer(PreservedHtmlTypeId, renderer)
        }
    }

    val uriHandler = LocalUriHandler.current
    CascadeDocumentPreview(
        blocks = blocks,
        modifier = modifier,
        registry = registry,
        theme = theme,
        // Unbounded: a task description is read in full, and this preview does not scroll,
        // so the host's own scrolling shows all of it.
        config = CascadeDocumentPreviewConfig.Unbounded,
        onOpenLink = { uri ->
            if (uri.matches(taskIdPattern)) {
                onTaskClick?.invoke(uri)
            } else {
                uriHandler.openUri(uri)
            }
        },
    )
}

private class PreservedMarkdownPreviewRenderer(
    private val allSpacePrefixes: List<String>,
    private val getTaskById: ((String) -> Task?)?,
    private val onTaskClick: ((String) -> Unit)?,
) : BlockPreviewRenderer<BlockType> {

    @Composable
    override fun RenderPreview(block: Block, modifier: Modifier, scope: BlockPreviewScope) {
        val raw = (block.content as? BlockContent.Custom)?.data?.get(RawMarkdownKey) as? String
        SimpleMarkdownText(
            markdown = raw.orEmpty(),
            modifier = modifier,
            allSpacePrefixes = allSpacePrefixes,
            getTaskById = getTaskById,
            onTaskClick = onTaskClick,
        )
    }
}
