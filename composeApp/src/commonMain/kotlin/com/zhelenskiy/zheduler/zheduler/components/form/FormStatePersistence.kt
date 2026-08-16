@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The part of a task form that survives process death.
 *
 * The whole form, not just the typed fields. What a dialog puts into the form is a finished edit
 * like any other — a status set, a connection added, a recurrence rule configured — and leaving
 * these out meant that stepping away from the edit screen for even a moment, to create a connected
 * task, silently rolled all of them back while the typed fields stayed.
 *
 * The dialog-owned values are stored as JSON and read back defensively: [null] means the record
 * could not be decoded, and the form keeps what it has rather than being blanked by a value that
 * an older or newer build wrote in a shape this one does not understand.
 */
data class PersistedFormState(
    val title: String?,
    val description: String?,
    val priority: String?,
    val estimatedTime: String?,
    val tags: PersistentSet<String>,
    val dueDate: Instant?,
    val status: TaskStatus?,
    val connections: PersistentSet<TaskConnection>?,
    val notifications: PersistentList<String>?,
    val recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>?,
    val autoUpdateStatusFromSubtasks: Boolean?,
)

/**
 * Stores a task form in the navigation entry's [SavedStateHandle], so that leaving the app in the
 * middle of writing a task does not lose it.
 *
 * Shared by the new-task and edit forms; they persist the same fields under the same keys, each in
 * its own back stack entry.
 */
class FormStatePersistence(private val savedStateHandle: SavedStateHandle) {

    /**
     * The stored form, or null if none was ever stored.
     *
     * The difference matters. A form whose due date and tags the user deliberately cleared stores
     * a null and an empty set — field for field, exactly what an absent record looks like — so
     * reading those as "nothing to restore" brought the cleared values back.
     */
    fun read(): PersistedFormState? {
        if (savedStateHandle.get<Boolean>(KEY_PRESENT) != true) return null
        return PersistedFormState(
            title = savedStateHandle[KEY_TITLE],
            description = savedStateHandle[KEY_DESCRIPTION],
            priority = savedStateHandle[KEY_PRIORITY],
            estimatedTime = savedStateHandle[KEY_ESTIMATED_TIME],
            tags = savedStateHandle.get<String>(KEY_TAGS)
                ?.let { runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull() }
                ?.toPersistentSet()
                ?: persistentSetOf(),
            dueDate = savedStateHandle.get<Long>(KEY_DUE_DATE)?.let(Instant::fromEpochMilliseconds),
            status = decode<TaskStatus>(KEY_STATUS),
            connections = decode<Set<TaskConnection>>(KEY_CONNECTIONS)?.toPersistentSet(),
            notifications = decode<List<String>>(KEY_NOTIFICATIONS)?.toPersistentList(),
            recurrenceRules = decode<List<Pair<RecurrenceRule, RecurrenceState>>>(KEY_RECURRENCE_RULES)
                ?.toPersistentList(),
            autoUpdateStatusFromSubtasks = savedStateHandle[KEY_AUTO_UPDATE_STATUS],
        )
    }

    private inline fun <reified T> decode(key: String): T? =
        savedStateHandle.get<String>(key)?.let { runCatching { Json.decodeFromString<T>(it) }.getOrNull() }

    fun write(state: PersistedFormState) {
        savedStateHandle[KEY_PRESENT] = true
        savedStateHandle[KEY_TITLE] = state.title
        savedStateHandle[KEY_DESCRIPTION] = state.description
        savedStateHandle[KEY_PRIORITY] = state.priority
        savedStateHandle[KEY_ESTIMATED_TIME] = state.estimatedTime
        savedStateHandle[KEY_TAGS] = Json.encodeToString<Set<String>>(state.tags)
        savedStateHandle[KEY_DUE_DATE] = state.dueDate?.toEpochMilliseconds()
        savedStateHandle[KEY_STATUS] = state.status?.let { Json.encodeToString<TaskStatus>(it) }
        savedStateHandle[KEY_CONNECTIONS] =
            state.connections?.let { Json.encodeToString<Set<TaskConnection>>(it) }
        savedStateHandle[KEY_NOTIFICATIONS] =
            state.notifications?.let { Json.encodeToString<List<String>>(it) }
        savedStateHandle[KEY_RECURRENCE_RULES] = state.recurrenceRules
            ?.let { Json.encodeToString<List<Pair<RecurrenceRule, RecurrenceState>>>(it) }
        savedStateHandle[KEY_AUTO_UPDATE_STATUS] = state.autoUpdateStatusFromSubtasks
    }

    fun clear() {
        savedStateHandle.remove<Boolean>(KEY_PRESENT)
        savedStateHandle.remove<String>(KEY_TITLE)
        savedStateHandle.remove<String>(KEY_DESCRIPTION)
        savedStateHandle.remove<String>(KEY_PRIORITY)
        savedStateHandle.remove<String>(KEY_ESTIMATED_TIME)
        savedStateHandle.remove<String>(KEY_TAGS)
        savedStateHandle.remove<Long>(KEY_DUE_DATE)
        savedStateHandle.remove<String>(KEY_STATUS)
        savedStateHandle.remove<String>(KEY_CONNECTIONS)
        savedStateHandle.remove<String>(KEY_NOTIFICATIONS)
        savedStateHandle.remove<String>(KEY_RECURRENCE_RULES)
        savedStateHandle.remove<Boolean>(KEY_AUTO_UPDATE_STATUS)
    }

    private companion object {
        /** Written alongside the fields, so an absent record can be told from a cleared one. */
        const val KEY_PRESENT = "form_present"
        const val KEY_TITLE = "form_title"
        const val KEY_DESCRIPTION = "form_description"
        const val KEY_PRIORITY = "form_priority"
        const val KEY_ESTIMATED_TIME = "form_estimated_time"
        const val KEY_TAGS = "form_tags"
        const val KEY_DUE_DATE = "form_due_date"
        const val KEY_STATUS = "form_status"
        const val KEY_CONNECTIONS = "form_connections"
        const val KEY_NOTIFICATIONS = "form_notifications"
        const val KEY_RECURRENCE_RULES = "form_recurrence_rules"
        const val KEY_AUTO_UPDATE_STATUS = "form_auto_update_status"
    }
}

/** What of this form would be restored after process death. */
fun TaskFormState.toPersistedState() = PersistedFormState(
    title = title,
    description = description,
    priority = priority,
    estimatedTime = estimatedTime,
    tags = tags,
    dueDate = dueDate,
    status = status,
    connections = connections,
    notifications = notifications,
    recurrenceRules = recurrenceRules,
    autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks,
)

/**
 * Overwrites every field this state covers.
 *
 * A stored record always describes a whole form — nothing is written until the form has been
 * restored — so it is applied as it stands, blanks included. Skipping the blanks resurrected a due
 * date or a set of tags the user had removed.
 */
fun PersistedFormState.applyTo(formState: TaskFormState) {
    formState.title = title.orEmpty()
    formState.description = description.orEmpty()
    formState.priority = priority.orEmpty()
    formState.estimatedTime = estimatedTime.orEmpty()
    formState.tags = tags
    formState.dueDate = dueDate
    // Except a value that would not decode: that is a record this build cannot read, not an edit
    // the user made, and overwriting a live form with it would lose more than it restored.
    status?.let { formState.status = it }
    connections?.let { formState.connections = it }
    autoUpdateStatusFromSubtasks?.let { formState.autoUpdateStatusFromSubtasks = it }
    if (notifications != null || recurrenceRules != null) {
        formState.restoreEntries(
            notifications = notifications ?: formState.notifications,
            recurrenceRules = recurrenceRules ?: formState.recurrenceRules,
        )
    }
}

/**
 * Restores this form from [persistence], then writes every later edit back to it.
 *
 * Both effects key on the form itself, because a form is rebuilt when the data it starts from
 * finally loads. Two rules follow from that.
 *
 * Nothing is written before the restore has run, or the freshly emptied rebuild would land on top
 * of what the user had already typed.
 *
 * Nothing is written until the form differs from what it was built with. The form that exists
 * before the data loads is empty and untouched; persisting it stored a blank record, which the
 * prefilled rebuild then dutifully restored over its own prefill — opening "copy task" gave a
 * blank form.
 */
@Composable
fun TaskFormState.persistedIn(persistence: FormStatePersistence) {
    var restored by remember(this) { mutableStateOf(false) }
    var edited by remember(this) { mutableStateOf(false) }
    val builtWith = remember(this) { toPersistedState() }

    LaunchedEffect(this) {
        persistence.read()?.applyTo(this@persistedIn)
        restored = true
    }

    LaunchedEffect(
        this, restored, title, description, priority, estimatedTime, tags, dueDate,
        status, connections, notifications, recurrenceRules, autoUpdateStatusFromSubtasks,
    ) {
        if (!restored) return@LaunchedEffect
        val current = toPersistedState()
        // Once it has diverged it keeps being written, so returning the form to its original
        // contents is recorded too rather than leaving an older record standing.
        if (!edited && current == builtWith) return@LaunchedEffect
        edited = true
        persistence.write(current)
    }
}
