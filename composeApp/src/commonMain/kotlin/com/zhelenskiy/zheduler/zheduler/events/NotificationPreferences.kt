package com.zhelenskiy.zheduler.zheduler.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.PlatformFile
import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the app sounds like, and the sounds the user has added to choose from.
 *
 * Held as state rather than read from disk each time, because the engine asks for it on every
 * alert and may be running in a background worker where a suspending read would be awkward. The
 * stored copy is the record; this is what the running app is going by.
 */
class NotificationPreferences(private val store: KStore<NotificationSettings>) {

    private val _settings = MutableStateFlow(NotificationSettings())
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    private val mutex = Mutex()
    private var read = false

    /** Reads what was chosen last time. A copy that cannot be read leaves the defaults standing. */
    suspend fun load() {
        mutex.withLock { readUnlessAlready() }
    }

    /** Sets what one of the three kinds of announcement sounds like. */
    suspend fun setSound(role: SoundRole, sound: ChosenSound) {
        update { it.with(role, sound) }
    }

    /**
     * Copies [file] into the app's own storage and offers it as a choice from now on.
     *
     * Returns what to call it, or `null` if it could not be read or copied — a file behind a
     * permission that has already lapsed, one the platform would not hand over, or a copy there
     * turned out to be no room for.
     */
    suspend fun addCustomSound(file: PlatformFile): CustomSound? {
        val added = addToSoundLibrary(file) ?: return null
        update { it.copy(library = it.library + added) }
        return added
    }

    /**
     * Forgets a sound the user added, and deletes the copy.
     *
     * Anything still asking for it keeps the name it was chosen under and falls back to the sound
     * it was stored alongside — silence is never what a forgotten file should turn into.
     */
    suspend fun removeCustomSound(sound: CustomSound) {
        SoundLibrary.remove(sound.id)
        update { settings ->
            SoundRole.entries
                .fold(settings.copy(library = settings.library - sound)) { carried, role ->
                    if (carried.forRole(role).custom == sound) {
                        carried.with(role, ChosenSound(carried.forRole(role).builtin))
                    } else {
                        carried
                    }
                }
        }
    }

    private suspend fun update(change: (NotificationSettings) -> NotificationSettings) {
        mutex.withLock {
            // What is on disk, before anything is written over it: a choice made in the moment
            // between the app starting and its settings being read would otherwise save the
            // defaults on top of them, taking every sound the user had added with it.
            readUnlessAlready()
            val updated = change(_settings.value)
            _settings.value = updated
            runCatching { store.set(updated) }
        }
    }

    /**
     * Reads the stored copy the first time anyone asks, and not again.
     *
     * Once, because a second read would undo a choice: on Android the background worker calls
     * [load] on the same instance the settings dialog is writing to, and a read landing between a
     * choice and its saving would put the old sound back while the new one went to disk.
     *
     * A read that fails counts as having happened. The alternative is trying again on every change
     * and writing the file each time it works, which for a copy that cannot be read at all — the
     * only way this fails — means asking the same question forever.
     */
    private suspend fun readUnlessAlready() {
        if (read) return
        read = true
        val stored = runCatching { store.get() }.getOrNull() ?: return
        _settings.value = stored.normalized()
    }
}

/**
 * What the app is set to sound like, or the defaults where nobody provided preferences.
 *
 * Wanted wherever a sound is chosen: the settings dialog sets these, and a picker on a reminder
 * has to resolve "App default" against them before it can play anything.
 */
@Composable
fun rememberAppSounds(): State<NotificationSettings> {
    val preferences = LocalNotificationPreferences.current
    val settings = remember(preferences) {
        preferences?.settings ?: MutableStateFlow(NotificationSettings())
    }
    return settings.collectAsState()
}

/**
 * The preferences, where there are any.
 *
 * Null by default rather than an error: the app bar is drawn by screens that tests render on their
 * own, and a preference nobody has provided is a reason to leave one button out, not to bring the
 * screen down.
 */
val LocalNotificationPreferences = compositionLocalOf<NotificationPreferences?> { null }
