package com.zhelenskiy.zheduler.zheduler.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which editor each task's description is edited and rendered with.
 *
 * Reads and writes go straight through [onPersist]; nothing above has to hold or forward
 * this, so the screens that show a description reach it directly rather than being handed it
 * from the top of the app.
 *
 * The default instance behind [LocalEditorSettings] is file-backed. Passing [onPersist] is
 * how tests and previews get one that keeps to itself.
 */
@Stable
class EditorSettingsState(
    descriptionEditorByTask: Map<String, DescriptionEditorKind> = emptyMap(),
    private val onPersist: (Map<String, DescriptionEditorKind>) -> Unit = {},
) {
    /** Per-task choices; tasks using [DefaultDescriptionEditor] are absent rather than listed. */
    var descriptionEditorByTask by mutableStateOf(descriptionEditorByTask)
        private set

    /**
     * The editor for [taskId]. A task with no choice of its own — including one that does not
     * exist yet — uses [DefaultDescriptionEditor].
     */
    fun descriptionEditorFor(taskId: String?): DescriptionEditorKind =
        taskId?.let { descriptionEditorByTask[it] } ?: DefaultDescriptionEditor

    /** Tasks the user has decided about since start, which storage must not speak over. */
    private val decidedSinceStart = mutableSetOf<String>()

    /**
     * Forgets the choices made for tasks whose ids [belongsToDeleted] recognises.
     *
     * Task ids are handed out per space as `PREFIX-1`, `PREFIX-2`, and they come back: delete a
     * task, or a whole space, and the next ones created take the same names. A choice left behind
     * then belongs to a task that no longer exists, and is inherited by whichever unrelated task
     * is given its id — opening in an editor its owner never asked for. Kept out of the repository
     * on purpose: this is a preference about a screen, not part of the task.
     */
    fun forget(belongsToDeleted: (taskId: String) -> Boolean) {
        val remaining = descriptionEditorByTask.filterKeys { !belongsToDeleted(it) }
        decidedSinceStart.removeAll { belongsToDeleted(it) }
        if (remaining == descriptionEditorByTask) return
        descriptionEditorByTask = remaining
        onPersist(remaining)
    }

    /** Remembers [kind] for [taskId]. A task with no id yet has nothing to remember it by. */
    fun setDescriptionEditorFor(taskId: String?, kind: DescriptionEditorKind) {
        if (taskId == null) return
        decidedSinceStart += taskId
        val updated = if (kind == DefaultDescriptionEditor) {
            descriptionEditorByTask - taskId
        } else {
            descriptionEditorByTask + (taskId to kind)
        }
        if (updated == descriptionEditorByTask) return
        descriptionEditorByTask = updated
        onPersist(updated)
    }

    /**
     * Applies what was read back from storage, without writing it out again.
     *
     * A choice made while the read was still in flight is the newer one and stands. Replacing the
     * map wholesale flipped such a task back to whatever the disk said, while the write for the
     * user's choice was already on its way — leaving the screen and the file disagreeing.
     */
    internal fun restore(byTask: Map<String, DescriptionEditorKind>) {
        descriptionEditorByTask = byTask.filterKeys { it !in decidedSinceStart } + descriptionEditorByTask
    }
}

/**
 * The app's persisted settings, created on first use by whoever needs them.
 *
 * Loading is asynchronous: until it lands every task reports [DefaultDescriptionEditor],
 * which is also what a task without a stored choice uses, so nothing flickers except a task
 * that was explicitly switched.
 */
private val persistedEditorSettings: EditorSettingsState by lazy {
    val store = createEditorSettingsStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One writer, newest wins. Launching a coroutine per change left their order to the
    // dispatcher, so of two quick switches the older could reach the file last and be what came
    // back on restart. Each value is the whole map, so dropping a superseded one loses nothing.
    val writes = Channel<Map<String, DescriptionEditorKind>>(Channel.CONFLATED)
    // Each write is guarded on its own. This scope has no exception handler, so a single refused
    // write — a full disk, a read-only volume, a browser quota — took the whole writer down with
    // it on Android, and every later choice was shown as applied and quietly never stored.
    scope.launch {
        for (byTask in writes) runCatching { store.set(EditorSettings(byTask)) }
    }

    val state = EditorSettingsState(onPersist = { writes.trySend(it) })
    // Likewise the read: a settings file that will not decode is worth no more than no file at
    // all, and throwing here killed the process the first time a description was shown.
    scope.launch {
        val stored = runCatching { store.get()?.descriptionEditorByTask }.getOrNull()
        // Applied on the main thread, where every choice the user makes is also applied. The read
        // runs off it, but the state it lands in is touched from composition event handlers, and
        // the guard that keeps a choice made mid-read from being spoken over is a plain set: from
        // another thread there is nothing to say the addition is even visible yet.
        withContext(Dispatchers.Main) { state.restore(stored.orEmpty()) }
    }
    state
}

/** Overridable so tests and previews are not tied to the file on disk. */
val LocalEditorSettings = staticCompositionLocalOf { persistedEditorSettings }
