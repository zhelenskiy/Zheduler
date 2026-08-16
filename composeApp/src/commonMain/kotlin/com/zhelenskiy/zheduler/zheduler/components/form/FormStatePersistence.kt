@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The part of a task form that survives process death.
 *
 * Only the plain fields are kept. Connections, notifications and recurrence rules are left out:
 * they are edited through their own dialogs, and a half-entered one is not worth restoring.
 */
data class PersistedFormState(
    val title: String?,
    val description: String?,
    val priority: String?,
    val estimatedTime: String?,
    val tags: PersistentSet<String>,
    val dueDate: Instant?,
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
        )
    }

    fun write(state: PersistedFormState) {
        savedStateHandle[KEY_PRESENT] = true
        savedStateHandle[KEY_TITLE] = state.title
        savedStateHandle[KEY_DESCRIPTION] = state.description
        savedStateHandle[KEY_PRIORITY] = state.priority
        savedStateHandle[KEY_ESTIMATED_TIME] = state.estimatedTime
        savedStateHandle[KEY_TAGS] = Json.encodeToString<Set<String>>(state.tags)
        savedStateHandle[KEY_DUE_DATE] = state.dueDate?.toEpochMilliseconds()
    }

    fun clear() {
        savedStateHandle.remove<Boolean>(KEY_PRESENT)
        savedStateHandle.remove<String>(KEY_TITLE)
        savedStateHandle.remove<String>(KEY_DESCRIPTION)
        savedStateHandle.remove<String>(KEY_PRIORITY)
        savedStateHandle.remove<String>(KEY_ESTIMATED_TIME)
        savedStateHandle.remove<String>(KEY_TAGS)
        savedStateHandle.remove<Long>(KEY_DUE_DATE)
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
}

/**
 * Restores this form from [persistence], then writes every later edit back to it.
 *
 * Both effects key on the form itself, because a form is rebuilt when the data it starts from
 * finally loads. Writing that freshly emptied form back before the restore has run would erase
 * what the user had already typed; the flag makes that ordering explicit rather than leaving it
 * to the order the two effects happen to be declared in.
 */
@Composable
fun TaskFormState.persistedIn(persistence: FormStatePersistence) {
    var restored by remember(this) { mutableStateOf(false) }

    LaunchedEffect(this) {
        persistence.read()?.applyTo(this@persistedIn)
        restored = true
    }

    LaunchedEffect(this, restored, title, description, priority, estimatedTime, tags, dueDate) {
        if (restored) persistence.write(toPersistedState())
    }
}
