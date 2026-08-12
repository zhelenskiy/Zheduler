package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Edits closer together than this are one undo step, so typing is not undone letter by letter. */
private val CoalesceWindow = 700.milliseconds

/** Bounds memory: a description is small, but the stack should not grow without limit. */
private const val MaxEntries = 200

/** The undo stack an editor keeps for itself, when it has one. */
@Stable
interface EditorHistory {
    val canUndo: Boolean
    val canRedo: Boolean
    fun undo()
    fun redo()
}

/**
 * Undo/redo for the description, in two layers.
 *
 * The mounted editor's own history goes first: it is finer grained, it puts the caret back,
 * and it covers changes that never reach the stored text at all — pressing Enter leaves an
 * empty paragraph, and a blank line has no Markdown spelling, so the description is byte for
 * byte what it was.
 *
 * Underneath sits a stack of description snapshots owned by the field. An editor's history
 * dies with it, so this is what carries a step across a switch between editors.
 *
 * Both layers stay in agreement because [record] recognises a value it has already seen:
 * whichever layer performs the step, the other follows rather than treating the result as a
 * fresh edit.
 */
@Stable
class DescriptionHistoryState(initial: String) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var current: String = initial
    private var lastRecord: TimeMark? = null

    private var snapshotsCanUndo by mutableStateOf(false)
    private var snapshotsCanRedo by mutableStateOf(false)

    /** Published by the editor currently on screen; null when it keeps no history. */
    var editor by mutableStateOf<EditorHistory?>(null)

    val canUndo: Boolean get() = editor?.canUndo == true || snapshotsCanUndo
    val canRedo: Boolean get() = editor?.canRedo == true || snapshotsCanRedo

    /** Notes an edit. Values this history itself produced are followed, not re-recorded. */
    fun record(value: String) {
        if (value == current) return

        // The editor undid or redid a step of its own: move with it instead of stacking a
        // new entry on top, which would make the layers disagree about where they are.
        if (past.lastOrNull() == value) {
            future.addLast(current)
            current = past.removeLast()
            lastRecord = null
            refresh()
            return
        }
        if (future.lastOrNull() == value) {
            past.addLast(current)
            current = future.removeLast()
            lastRecord = null
            refresh()
            return
        }

        val coalescing = lastRecord?.elapsedNow()?.let { it < CoalesceWindow } == true
        if (!coalescing) {
            past.addLast(current)
            if (past.size > MaxEntries) past.removeFirst()
        }
        current = value
        future.clear()
        lastRecord = TimeSource.Monotonic.markNow()
        refresh()
    }

    /**
     * Steps back. Returns the text to restore, or null when the editor handled it itself or
     * there was nothing to undo.
     */
    fun undo(): String? {
        editor?.takeIf { it.canUndo }?.let { editorHistory ->
            editorHistory.undo()
            return null
        }
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        current = previous
        // The next edit opens a new step rather than merging into the one just undone.
        lastRecord = null
        refresh()
        return previous
    }

    fun redo(): String? {
        editor?.takeIf { it.canRedo }?.let { editorHistory ->
            editorHistory.redo()
            return null
        }
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        current = next
        lastRecord = null
        refresh()
        return next
    }

    private fun refresh() {
        snapshotsCanUndo = past.isNotEmpty()
        snapshotsCanRedo = future.isNotEmpty()
    }
}

@Composable
internal fun UndoRedoControls(
    history: DescriptionHistoryState,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        HistoryButton(
            icon = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            enabled = history.canUndo,
            onClick = { history.undo()?.let(onRestore) },
        )
        HistoryButton(
            icon = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            enabled = history.canRedo,
            onClick = { history.redo()?.let(onRestore) },
        )
    }
}

@Composable
private fun HistoryButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}
