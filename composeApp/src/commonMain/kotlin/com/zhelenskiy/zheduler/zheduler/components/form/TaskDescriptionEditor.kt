package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Whether this platform ships the block editor. False on JS, which cascade-editor
 * publishes no artifact for — there the description is always edited as Markdown and no
 * editor choice is offered.
 */
expect val isRichTaskDescriptionEditorAvailable: Boolean

/**
 * Block-based WYSIWYG editor for a task description held as a Markdown [String].
 *
 * Decodes [markdown] on the way in and re-encodes it on every edit, so the stored format
 * stays Markdown and the two description editors remain interchangeable.
 *
 * [markdown] is the source of truth: pushing a new value in replaces the editor content,
 * which is how draft restore and "discard changes" reload it. [onMarkdownChange] only
 * fires for edits the user actually made, never for the codec's own canonicalization of
 * the value that was loaded.
 *
 * [preview] is rendered only when the description has to fall back to Markdown source —
 * a WYSIWYG document needs no preview, raw source does.
 *
 * [history] is where the editor publishes its own undo stack, which sits under the field's
 * description-level one.
 */
@Composable
expect fun TaskDescriptionEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
    history: DescriptionHistoryState? = null,
)
