package com.zhelenskiy.zheduler.zheduler.events

import android.media.Ringtone
import android.media.RingtoneManager
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Whatever the preview is playing, so that a second choice stops the first.
 *
 * A ringtone left running is not stopped by starting another — the two would overlap, and a menu
 * gone through quickly would end up playing all of them at once.
 */
@Volatile
private var playing: Ringtone? = null
private val turn = Mutex()

/** Outlives the menu, which is dismissed by the same click that starts the sound. */
private val previews = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Volatile
private var timeLimit: Job? = null

/**
 * Long enough to recognise a sound by, and short enough not to be stuck with.
 *
 * The alarm tone runs for half a minute at alarm volume and the menu is gone by then, so without
 * this the only way out of hearing it through would be to pick a different sound.
 */
private val LONG_ENOUGH = 5.seconds

actual suspend fun previewNotificationSound(sound: ChosenSound) = turn.withLock {
    val context = androidApplication()
    withContext(Dispatchers.IO) {
        // Inside, so that a caller cancelled on the way in leaves the last preview as it was,
        // still under its own time limit, rather than uncapped with nobody left to stop it.
        timeLimit?.cancel()
        runCatching { playing?.stop() }
        playing = null
        val uri = uriFor(context, sound) ?: return@withContext
        runCatching {
            playing = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributesFor(sound)?.let { audioAttributes = it }
                play()
            }
        }
        val started = playing ?: return@withContext
        timeLimit = previews.launch {
            delay(LONG_ENOUGH)
            // Only the one this call started: a later choice is a later choice's to stop.
            runCatching { if (playing === started) started.stop() }
        }
    }
    Unit
}
