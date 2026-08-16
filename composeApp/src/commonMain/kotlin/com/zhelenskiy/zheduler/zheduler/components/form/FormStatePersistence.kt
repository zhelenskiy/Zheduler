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
    /**
     * The connections the form was built from, before the user touched anything.
     *
     * Kept because the task's connections can change in the database while the form is off the
     * screen — creating a connected task from the edit screen writes the other half of the link —
     * and telling that apart from the user's own edits needs the point both started from.
     */
    val connectionsBase: PersistentSet<TaskConnection>?,
    val notifications: PersistentList<String>?,
    val recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>?,
    val autoUpdateStatusFromSubtasks: Boolean?,
    /**
     * Which of these fields the user actually changed, or null in a record written before this
     * was tracked.
     *
     * A record is applied to whatever form is on screen when it is read back, and that is not
     * always the form it was written from: opening "copy task" builds an empty form first and
     * rebuilds it when the copied task arrives. Applying the record whole then put the empty form
     * back over the prefill, and the copy was gone. Only what the user touched is applied.
     */
    val editedFields: Set<String>? = null,
)

/** Field names for [PersistedFormState.editedFields]. Stored, so they may not be renamed freely. */
internal object FormField {
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val PRIORITY = "priority"
    const val ESTIMATED_TIME = "estimatedTime"
    const val TAGS = "tags"
    const val DUE_DATE = "dueDate"
    const val STATUS = "status"
    const val CONNECTIONS = "connections"
    const val NOTIFICATIONS = "notifications"
    const val RECURRENCE_RULES = "recurrenceRules"
    const val AUTO_UPDATE_STATUS = "autoUpdateStatus"
}

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
            connectionsBase = decode<Set<TaskConnection>>(KEY_CONNECTIONS_BASE)?.toPersistentSet(),
            notifications = decode<List<String>>(KEY_NOTIFICATIONS)?.toPersistentList(),
            recurrenceRules = decode<List<Pair<RecurrenceRule, RecurrenceState>>>(KEY_RECURRENCE_RULES)
                ?.toPersistentList(),
            autoUpdateStatusFromSubtasks = savedStateHandle[KEY_AUTO_UPDATE_STATUS],
            editedFields = decode<Set<String>>(KEY_EDITED_FIELDS),
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
        savedStateHandle[KEY_CONNECTIONS_BASE] =
            state.connectionsBase?.let { Json.encodeToString<Set<TaskConnection>>(it) }
        savedStateHandle[KEY_NOTIFICATIONS] =
            state.notifications?.let { Json.encodeToString<List<String>>(it) }
        savedStateHandle[KEY_RECURRENCE_RULES] = state.recurrenceRules
            ?.let { Json.encodeToString<List<Pair<RecurrenceRule, RecurrenceState>>>(it) }
        savedStateHandle[KEY_AUTO_UPDATE_STATUS] = state.autoUpdateStatusFromSubtasks
        savedStateHandle[KEY_EDITED_FIELDS] =
            state.editedFields?.let { Json.encodeToString<Set<String>>(it) }
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
        savedStateHandle.remove<String>(KEY_CONNECTIONS_BASE)
        savedStateHandle.remove<String>(KEY_NOTIFICATIONS)
        savedStateHandle.remove<String>(KEY_RECURRENCE_RULES)
        savedStateHandle.remove<Boolean>(KEY_AUTO_UPDATE_STATUS)
        savedStateHandle.remove<String>(KEY_EDITED_FIELDS)
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
        const val KEY_CONNECTIONS_BASE = "form_connections_base"
        const val KEY_NOTIFICATIONS = "form_notifications"
        const val KEY_RECURRENCE_RULES = "form_recurrence_rules"
        const val KEY_AUTO_UPDATE_STATUS = "form_auto_update_status"
        const val KEY_EDITED_FIELDS = "form_edited_fields"
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
    connectionsBase = connections,
    notifications = notifications,
    recurrenceRules = recurrenceRules,
    autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks,
)

/** The names of the fields where this record differs from [base]; see [PersistedFormState.editedFields]. */
internal fun PersistedFormState.fieldsDifferingFrom(base: PersistedFormState): Set<String> = buildSet {
    if (title != base.title) add(FormField.TITLE)
    if (description != base.description) add(FormField.DESCRIPTION)
    if (priority != base.priority) add(FormField.PRIORITY)
    if (estimatedTime != base.estimatedTime) add(FormField.ESTIMATED_TIME)
    if (tags != base.tags) add(FormField.TAGS)
    if (dueDate != base.dueDate) add(FormField.DUE_DATE)
    if (status != base.status) add(FormField.STATUS)
    if (connections != base.connections) add(FormField.CONNECTIONS)
    if (notifications != base.notifications) add(FormField.NOTIFICATIONS)
    if (recurrenceRules != base.recurrenceRules) add(FormField.RECURRENCE_RULES)
    if (autoUpdateStatusFromSubtasks != base.autoUpdateStatusFromSubtasks) add(FormField.AUTO_UPDATE_STATUS)
}

/**
 * Puts back what the user changed, and only that.
 *
 * A changed field is applied as it stands, blanks included: a due date or a tag set the user
 * cleared is an edit like any other, and skipping the blanks used to resurrect it. What they never
 * touched is left as the form has it, which is how a form rebuilt from data that arrived late —
 * the task being copied, say — keeps its prefill instead of being flattened by a record written
 * before that data existed.
 *
 * A record from a build that did not track this ([PersistedFormState.editedFields] null) is
 * applied whole, as it used to be.
 */
fun PersistedFormState.applyTo(formState: TaskFormState) {
    val edited = editedFields
    fun changed(field: String) = edited == null || field in edited

    if (changed(FormField.TITLE)) formState.title = title.orEmpty()
    if (changed(FormField.DESCRIPTION)) formState.description = description.orEmpty()
    if (changed(FormField.PRIORITY)) formState.priority = priority.orEmpty()
    if (changed(FormField.ESTIMATED_TIME)) formState.estimatedTime = estimatedTime.orEmpty()
    if (changed(FormField.TAGS)) formState.tags = tags
    if (changed(FormField.DUE_DATE)) formState.dueDate = dueDate
    // A value that would not decode is a record this build cannot read, not an edit the user made,
    // and overwriting a live form with it would lose more than it restored.
    if (changed(FormField.STATUS)) status?.let { formState.status = it }
    if (changed(FormField.CONNECTIONS)) connections?.let { formState.connections = it }
    if (changed(FormField.AUTO_UPDATE_STATUS)) {
        autoUpdateStatusFromSubtasks?.let { formState.autoUpdateStatusFromSubtasks = it }
    }
    val restoredNotifications = notifications.takeIf { changed(FormField.NOTIFICATIONS) }
    val restoredRules = recurrenceRules.takeIf { changed(FormField.RECURRENCE_RULES) }
    if (restoredNotifications != null || restoredRules != null) {
        formState.restoreEntries(
            notifications = restoredNotifications ?: formState.notifications,
            recurrenceRules = restoredRules ?: formState.recurrenceRules,
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
        // The base travels with the record: what the form started from, not what it holds now.
        persistence.write(
            current.copy(
                connectionsBase = builtWith.connections,
                editedFields = current.fieldsDifferingFrom(builtWith),
            )
        )
    }
}
