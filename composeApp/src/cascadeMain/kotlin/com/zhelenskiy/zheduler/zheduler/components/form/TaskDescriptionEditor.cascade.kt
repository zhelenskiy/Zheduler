@file:OptIn(ExperimentalCascadeMarkdownApi::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.markdown.withBareTaskReferenceLinks
import io.github.linreal.cascade.editor.action.DeleteBlock
import io.github.linreal.cascade.editor.action.InsertBlock
import io.github.linreal.cascade.editor.action.UpdateBlockContent
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.markdown.ExperimentalCascadeMarkdownApi
import io.github.linreal.cascade.editor.markdown.MarkdownDecodeWarning
import io.github.linreal.cascade.editor.markdown.MarkdownEncodeResult
import io.github.linreal.cascade.editor.markdown.MarkdownEncodeWarning
import io.github.linreal.cascade.editor.markdown.MarkdownWarning
import io.github.linreal.cascade.editor.markdown.MarkdownEditModeRecommendation
import io.github.linreal.cascade.editor.markdown.MarkdownFidelityImpact
import io.github.linreal.cascade.editor.markdown.MarkdownProfile
import io.github.linreal.cascade.editor.markdown.MarkdownSchema
import io.github.linreal.cascade.editor.markdown.loadFromMarkdown
import io.github.linreal.cascade.editor.markdown.toMarkdown
import io.github.linreal.cascade.editor.markdown.toMarkdownWithReport
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.rememberEditorState
import io.github.linreal.cascade.editor.theme.CascadeEditorTheme
import io.github.linreal.cascade.editor.ui.CascadeEditor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Editor viewport bounds. Beyond the maximum the editor scrolls internally. */
private val EditorMinHeight = 150.dp
private val EditorMaxHeight = 340.dp

/** Quiet period before an edit is reported upwards, so typing does not thrash the form. */
private const val SyncDebounceMillis = 250L

private val EditorShape = RoundedCornerShape(12.dp)

actual val isRichTaskDescriptionEditorAvailable: Boolean = true

@Composable
actual fun TaskDescriptionEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    preview: @Composable () -> Unit,
    history: DescriptionHistoryState?,
) {
    // Part of the storage contract: analyze, decode and encode must all use the same
    // profile, and it must not change within a session.
    val profile = MarkdownProfile.Default
    val stateHolder = rememberEditorState()
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    val sync = remember { MarkdownSync() }
    val deleteBlock: (BlockId) -> Unit = remember(stateHolder, textStates, spanStates) {
        { blockId ->
            // DeleteBlock has no empty-document guard, and a document with no blocks has
            // nothing to type into, so the last block is replaced rather than removed.
            val isLastBlock = stateHolder.state.blocks.size <= 1
            stateHolder.dispatchStructuralAction(DeleteBlock(blockId), textStates, spanStates)
            if (isLastBlock) {
                stateHolder.dispatchStructuralAction(
                    InsertBlock(Block.paragraph()),
                    textStates,
                    spanStates,
                )
            }
        }
    }
    val updateBlock: (BlockId, BlockContent) -> Unit =
        remember(stateHolder, textStates, spanStates) {
            { blockId, content ->
                // dispatch() bypasses history by design, so a table cell or source edit made
                // through it could never be undone. This is the history-aware path.
                stateHolder.dispatchStructuralAction(
                    UpdateBlockContent(blockId, content),
                    textStates,
                    spanStates,
                )
            }
        }
    val navigator = remember(stateHolder, textStates, deleteBlock) {
        BlockNavigator(stateHolder, textStates, deleteBlock)
    }
    val registry = remember(stateHolder, textStates, spanStates, navigator, updateBlock) {
        createDescriptionEditorRegistry(
            deleteBlock = deleteBlock,
            updateBlock = updateBlock,
            exitBlock = { blockId, exit ->
                navigator.focusTextBlockBeside(
                    fromBlockId = blockId,
                    direction = if (exit == TableExit.Before) -1 else 1,
                )
            },
            onCellFocusChange = { focused -> navigator.cellHasFocus = focused },
        )
    }
    val slashRegistry = remember { createDescriptionSlashCommands() }

    if (history != null) {
        DisposableEffect(history, stateHolder) {
            history.editor = object : EditorHistory {
                override val canUndo: Boolean get() = stateHolder.canUndo
                override val canRedo: Boolean get() = stateHolder.canRedo
                override fun undo() = stateHolder.undo()
                override fun redo() = stateHolder.redo()
            }
            onDispose { history.editor = null }
        }
    }

    val currentOnMarkdownChange by rememberUpdatedState(onMarkdownChange)

    LaunchedEffect(markdown) {
        if (!sync.needsLoad(markdown)) return@LaunchedEffect
        val decoded = stateHolder.loadFromMarkdown(markdown, textStates, spanStates, profile)
        val baseline = if (decoded.isSuccess) {
            stateHolder.toMarkdown(textStates, spanStates, profile)
                ?.let(::withBareTaskReferenceLinks)
        } else {
            // An aborted decode leaves the editor holding the previous document, so there
            // is nothing safe to edit here.
            null
        }
        sync.onLoaded(source = markdown, baseline = baseline)
        sync.preservedKinds = decoded.warnings
            .filterIsInstance<MarkdownDecodeWarning.PreservedSyntax>()
            .map { it.kind }
            .distinct()

        // Deliberately weaker than MarkdownSchema.analyze, which recommends raw editing for
        // any preserved syntax at all. Preservation is lossless — the codec re-emits those
        // slices byte for byte and they are rendered as editable source blocks — so the
        // block editor is kept for them. What genuinely cannot be edited natively is a
        // failed decode, real data loss, or a canonical form that is not a fixpoint, since
        // that would drift a little on every save/reload cycle.
        sync.rawFallback = baseline == null ||
            decoded.warnings.any { warning ->
                warning.impact == MarkdownFidelityImpact.DataLoss ||
                    warning.impact == MarkdownFidelityImpact.Fatal
            } ||
            !isCanonicalFixpoint(baseline, profile)
    }

    if (sync.rawFallback) {
        RawMarkdownField(
            markdown = markdown,
            // Routed through sync so our own writes are not mistaken for an external
            // replacement, which would reload on every keystroke.
            onMarkdownChange = { edited ->
                sync.onRawEdit(edited)
                currentOnMarkdownChange(edited)
            },
            label = label,
            modifier = modifier,
        )
        RawFallbackNotice(preservedKinds = sync.preservedKinds)
        preview()
        return
    }

    /** Hands [result] to the form, unless storing it would lose part of the document. */
    fun applyEncoded(result: MarkdownEncodeResult) {
        val payload = result.markdown?.let(::withBareTaskReferenceLinks) ?: return
        val lossy = result.warnings.filter { warning ->
            warning.impact == MarkdownFidelityImpact.DataLoss ||
                warning.impact == MarkdownFidelityImpact.Fatal
        }
        val blocking = lossy.filterNot { warning ->
            isBlankBlockNoise(warning, stateHolder.state.blocks, textStates)
        }
        if (blocking.isNotEmpty()) {
            // Storing this would lose part of it, so the last good text is kept —
            // but silently discarding what someone just typed is worse than saying so.
            sync.unstorableReasons = blocking.map(::describeEncodeWarning).distinct()
            return
        }
        sync.unstorableReasons = emptyList()
        sync.pushValue(payload)?.let(currentOnMarkdownChange)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { stateHolder.toMarkdownWithReport(textStates, spanStates, profile) }
            .collectLatest { result ->
                // Cancelled and restarted by the next edit, so this only fires once the
                // user pauses.
                delay(SyncDebounceMillis)
                applyEncoded(result)
            }
    }

    // The debounce above means the newest edits have not reached the form yet when the editor
    // goes away — on back, on switching to the raw Markdown field. Cancelling the effect would
    // simply drop them: pressing back within the debounce window reported no unsaved changes and
    // left without them.
    val flushPendingEdits by rememberUpdatedState {
        applyEncoded(stateHolder.toMarkdownWithReport(textStates, spanStates, profile))
    }
    // Going away is not the only time they are needed. Save reads the form and writes the task
    // there and then, while this editor is still on screen and still holding the last few
    // keystrokes; the flush below happens afterwards, into a form nobody will read again. So the
    // form is given a way to ask first.
    val pendingEdits = LocalPendingEdits.current
    DisposableEffect(pendingEdits) {
        val unregister = pendingEdits.register { flushPendingEdits() }
        onDispose {
            unregister()
            flushPendingEdits()
        }
    }

    val scheme = MaterialTheme.colorScheme
    val theme = if (scheme.surface.luminance() < 0.5f) {
        CascadeEditorTheme.dark()
    } else {
        CascadeEditorTheme.light()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )

        // CascadeEditor scrolls its blocks in a LazyColumn, so it needs a bounded height:
        // the surrounding form measures children with infinite max height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EditorMinHeight, max = EditorMaxHeight)
                .clip(EditorShape)
                .background(scheme.surfaceContainerLowest, EditorShape)
                .border(1.dp, scheme.outline, EditorShape)
                .padding(vertical = 4.dp)
                // Previewed rather than bubbled: a text field consumes arrow keys even when
                // the caret has nowhere left to go, so a bubbling handler would never run.
                // BlockNavigator only acts at a block's first or last offset.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionRight -> navigator.onArrow(1)
                        Key.DirectionUp, Key.DirectionLeft -> navigator.onArrow(-1)
                        Key.Backspace -> navigator.onBackspace()
                        else -> false
                    }
                },
        ) {
            CascadeEditor(
                stateHolder = stateHolder,
                textStates = textStates,
                spanStates = spanStates,
                registry = registry,
                slashRegistry = slashRegistry,
                theme = theme,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        UnstorableEditNotice(reasons = sync.unstorableReasons)
    }
}

/**
 * Shown while the editor holds something that cannot be written back as Markdown.
 *
 * The text stays on screen but is not stored, so without this the edit simply disappears at
 * save time with no explanation.
 */
@Composable
private fun UnstorableEditNotice(reasons: List<String>) {
    if (reasons.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "This change is not being saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                reasons.forEach { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/**
 * True for the codec's complaint about an empty paragraph.
 *
 * A blank line has no Markdown spelling — blank lines separate blocks rather than being one —
 * so the encoder reports "no Markdown encoding produced output" and calls it data loss.
 * Nothing is actually lost, and treating it as loss would refuse every save made while an
 * empty line sits in the middle of the document, which is the state right after pressing
 * Enter.
 */
private fun isBlankBlockNoise(
    warning: MarkdownWarning,
    blocks: List<Block>,
    textStates: BlockTextStates,
): Boolean {
    if (warning !is MarkdownEncodeWarning.UnsupportedBlock) return false
    val blockId = warning.blockId ?: return false
    val block = blocks.firstOrNull { it.id == blockId } ?: return false
    if (!block.type.supportsText) return false
    // Live text first: the snapshot can lag a keystroke behind what was encoded.
    val text = textStates.getVisibleText(blockId) ?: (block.content as? BlockContent.Text)?.text
    return text.isNullOrBlank()
}

/** Plain-language version of an encode warning; the codec's own wording is internal jargon. */
private fun describeEncodeWarning(warning: MarkdownWarning): String = when (warning) {
    is MarkdownEncodeWarning.DroppedSpanOverlap ->
        "Some text cannot be written as Markdown — usually a line starting or ending with " +
            "a space, or overlapping formatting."

    is MarkdownEncodeWarning.AmbiguousEmphasis ->
        "Overlapping bold or italic here cannot be written as Markdown."

    is MarkdownEncodeWarning.DroppedAttribute ->
        "\"${warning.attr}\" cannot be written as Markdown: ${warning.reason}"

    is MarkdownEncodeWarning.UnsupportedBlock ->
        "A block here cannot be written as Markdown: ${warning.reason}"

    is MarkdownEncodeWarning.UnsupportedSpan ->
        "Formatting here cannot be written as Markdown: ${warning.reason}"

    is MarkdownEncodeWarning.OutputLimitExceeded ->
        "The description is too long to save (limit ${warning.limit} characters)."

    is MarkdownEncodeWarning.LimitExceeded ->
        "The description exceeds the ${warning.kind} limit of ${warning.limit}."

    else -> "Part of this change cannot be written as Markdown."
}

/**
 * Whether re-encoding the canonical form reproduces it exactly.
 *
 * Editing is only safe when the stored text is a fixpoint of decode→encode; otherwise every
 * save would rewrite the description slightly and the next load would rewrite it again.
 */
private fun isCanonicalFixpoint(baseline: String, profile: MarkdownProfile): Boolean {
    val blocks = MarkdownSchema.decode(baseline, profile) ?: return false
    return MarkdownSchema.encode(blocks, profile) == baseline
}

/**
 * Explains, where it cannot be missed, why the formatting controls are not here.
 *
 * Without this the field just looks broken: the picker still says "Rich text" while a plain
 * text box is on screen.
 */
@Composable
private fun RawFallbackNotice(preservedKinds: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = rawFallbackNotice(preservedKinds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Names what forced Markdown-source editing, from the codec's own preservation warnings.
 *
 * An unmapped kind is reported by its raw codec name rather than as vague "formatting":
 * the list of escalated constructs grows with the library, and a user staring at a plain
 * text box deserves a specific reason either way.
 */
private fun rawFallbackNotice(preservedKinds: List<String>): String {
    val named = preservedKinds.map { preservedKindName(it) }.distinct()
    val detail = if (named.isEmpty()) "" else " (${named.joinToString(", ")})"
    return "Editing as Markdown source. This description cannot be rebuilt exactly by the " +
        "rich editor$detail, so it is kept as written. The preview below shows how it will " +
        "look."
}

/**
 * Keeps a Markdown [String] and the editor in step.
 *
 * Two things need tracking. `lastPushed` separates our own writes from an external
 * replacement — a restored draft or a discarded edit — so ordinary typing never reloads
 * the document under the caret. `baseline` and `source` absorb the codec's
 * canonicalization (`_x_` becomes `*x*`, list markers and blank lines get normalized):
 * without them, merely opening a task would look like an edit to the form's
 * unsaved-changes check and offer to save a reformat.
 */
private class MarkdownSync {
    /** True when the stored Markdown does not round-trip and must be edited raw. */
    var rawFallback by mutableStateOf(false)

    /** Codec preservation kinds behind [rawFallback], used to explain it to the user. */
    var preservedKinds by mutableStateOf(emptyList<String>())

    /** Why the current editor content cannot be stored, empty when it can. */
    var unstorableReasons by mutableStateOf(emptyList<String>())

    /** Markdown the editor was loaded from, re-emitted if edits return to [baseline]. */
    private var source: String = ""

    /** Canonical encode of [source], or null if it could not be encoded. */
    private var baseline: String? = null

    /** Last value handed upwards, so it is not read back as an external change. */
    private var lastPushed: String? = null

    fun needsLoad(markdown: String): Boolean = markdown != lastPushed

    fun onLoaded(source: String, baseline: String?) {
        this.source = source
        this.baseline = baseline
        lastPushed = source
    }

    fun onRawEdit(markdown: String) {
        source = markdown
        lastPushed = markdown
    }

    /** The value to store for [encoded], or null when nothing user-visible changed. */
    fun pushValue(encoded: String): String? {
        val knownBaseline = baseline ?: return null
        val next = if (encoded == knownBaseline) source else encoded
        if (next == lastPushed) return null
        lastPushed = next
        return next
    }
}
