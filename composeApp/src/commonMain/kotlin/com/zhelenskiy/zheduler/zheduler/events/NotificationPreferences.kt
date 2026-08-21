package com.zhelenskiy.zheduler.zheduler.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** What the app sounds like when a reminder has not asked for something of its own. */
@Serializable
data class NotificationSettings(
    val defaultSound: NotificationSound = NotificationSound.Default,
)

/**
 * The sound everything other than a reminder makes: a deadline arriving, a rule coming round, a
 * status the app changed by itself.
 *
 * Held as state rather than read from disk each time, because the engine asks for it on every
 * alert and may be running in a background worker where a suspending read would be awkward. The
 * stored copy is the record; this is what the running app is going by.
 */
class NotificationPreferences(private val store: KStore<NotificationSettings>) {

    private val _defaultSound = MutableStateFlow(NotificationSound.Default)
    val defaultSound: StateFlow<NotificationSound> = _defaultSound.asStateFlow()

    private val mutex = Mutex()
    private var chosenSince = false

    /**
     * Reads what was chosen last time. A copy that cannot be read leaves the default standing.
     *
     * A choice made since this object was built is not overwritten: on Android the background
     * worker loads into the same instance the settings menu is writing to, and a read that landed
     * between the choice and the disk would put the old sound back while the new one was saved.
     */
    suspend fun load() {
        mutex.withLock {
            if (chosenSince) return
            val stored = runCatching { store.get() }.getOrNull() ?: return
            _defaultSound.value = stored.defaultSound
        }
    }

    suspend fun setDefaultSound(sound: NotificationSound) {
        mutex.withLock {
            _defaultSound.value = sound
            chosenSince = true
            runCatching { store.set(NotificationSettings(sound)) }
        }
    }
}

/**
 * What the app is set to sound like, or the platform's own where nobody provided preferences.
 *
 * Wanted in two places — the menu that sets it, and the reminder picker, where "App default" has
 * to be resolved before it can be played.
 */
@Composable
fun rememberDefaultNotificationSound(): State<NotificationSound> {
    val preferences = LocalNotificationPreferences.current
    val sounds = remember(preferences) {
        preferences?.defaultSound ?: MutableStateFlow(NotificationSound.Default)
    }
    return sounds.collectAsState()
}

/**
 * The preferences, where there are any.
 *
 * Null by default rather than an error: the app bar menu is drawn by screens that tests render on
 * their own, and a preference nobody has provided is a reason to leave one menu entry out, not to
 * bring the screen down.
 */
val LocalNotificationPreferences = compositionLocalOf<NotificationPreferences?> { null }
