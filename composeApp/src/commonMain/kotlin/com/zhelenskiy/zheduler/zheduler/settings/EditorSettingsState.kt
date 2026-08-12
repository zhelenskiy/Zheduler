package com.zhelenskiy.zheduler.zheduler.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Remembers [kind] for [taskId]. A task with no id yet has nothing to remember it by. */
    fun setDescriptionEditorFor(taskId: String?, kind: DescriptionEditorKind) {
        if (taskId == null) return
        val updated = if (kind == DefaultDescriptionEditor) {
            descriptionEditorByTask - taskId
        } else {
            descriptionEditorByTask + (taskId to kind)
        }
        if (updated == descriptionEditorByTask) return
        descriptionEditorByTask = updated
        onPersist(updated)
    }

    /** Applies what was read back from storage, without writing it out again. */
    internal fun restore(byTask: Map<String, DescriptionEditorKind>) {
        descriptionEditorByTask = byTask
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
    lateinit var state: EditorSettingsState
    state = EditorSettingsState(
        onPersist = { byTask -> scope.launch { store.set(EditorSettings(byTask)) } },
    )
    scope.launch { state.restore(store.get()?.descriptionEditorByTask.orEmpty()) }
    state
}

/** Overridable so tests and previews are not tied to the file on disk. */
val LocalEditorSettings = staticCompositionLocalOf { persistedEditorSettings }
