@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import ca.gosyer.appdirs.AppDirs
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.file.storeOf
import kotlinx.io.files.Path
import java.io.File
import kotlin.time.ExperimentalTime

actual fun createScheduleStore(): ScheduleStore {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return KStoreScheduleStore(storeOf(Path("$dataDir/schedule_state.json"), default = ScheduleState()))
}

actual fun createNotificationSettingsStore(): KStore<NotificationSettings> {
    val appDirs = AppDirs {
        appName = "Zheduler"
        appAuthor = "zhelenskiy"
    }
    val dataDir = appDirs.getUserDataDir()
    File(dataDir).mkdirs()
    return storeOf(Path("$dataDir/notification_settings.json"), default = NotificationSettings())
}

actual fun createEventNotifier(): EventNotifier = AndroidEventNotifier(androidApplication())

/**
 * Posts to the system notification shade.
 *
 * From API 33 the user may have refused the permission, in which case `notify` is ignored rather
 * than failing — the schedule still advances, which is what matters for the task's own state.
 */
class AndroidEventNotifier(private val context: Context) : EventNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    override suspend fun post(alert: TaskAlert) {
        // Resolved before the channel is chosen, or a sound the user has since removed would make
        // a channel of its own, named after a file that is gone and playing something else — and
        // a channel is never reused, so it would sit in the system's settings for good.
        val channel = channelFor(alert.sound.playable())
        val notification = Notification.Builder(context, channel)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setWhen(alert.at.toEpochMilliseconds())
            .build()

        // The key, not a counter: a redelivery of the same event replaces its notification instead
        // of stacking a second copy.
        manager.notify(alert.id, alert.id.hashCode(), notification)
    }

    /**
     * The channel that plays [sound], made if it is not there already.
     *
     * A channel's sound is fixed when it is created and cannot be changed afterwards — the user
     * owns it from that point on. So a sound is not a property of the notification here but of the
     * channel it is posted to, and there is one channel per sound the app can ask for. They are
     * named for the sound rather than numbered, so the notification settings screen reads as a
     * list of the app's own vocabulary.
     */
    private fun channelFor(sound: ChosenSound): String {
        retireTheOldChannel()
        val id = channelId(sound)
        manager.createNotificationChannel(channelWith(sound, importanceFor(sound)))
        return id
    }

    private fun channelWith(sound: ChosenSound, importance: Int) =
        NotificationChannel(channelId(sound), channelName(sound), importance).apply {
            setSound(uriFor(context, sound), audioAttributesFor(sound))
        }

    private fun channelId(sound: ChosenSound) =
        sound.custom?.let { customChannelId(it.id) }
            ?: "$CHANNEL_PREFIX.${sound.builtin.name.lowercase()}"

    private fun channelName(sound: ChosenSound): String {
        sound.custom?.let { return "Task reminders (${it.label})" }
        return when (sound.builtin) {
            NotificationSound.Default, NotificationSound.System -> "Task reminders (system sound)"
            NotificationSound.Silent -> "Task reminders (silent)"
            NotificationSound.Alarm -> "Task reminders (alarm)"
            NotificationSound.Chime -> "Task reminders (chime)"
            NotificationSound.Bell -> "Task reminders (bell)"
        }
    }

    /** An alarm is meant to interrupt; the rest are ordinary notifications. */
    private fun importanceFor(sound: ChosenSound) =
        if (sound.builtin == NotificationSound.Alarm) NotificationManager.IMPORTANCE_HIGH
        else NotificationManager.IMPORTANCE_DEFAULT

    /**
     * Removes the single channel this app posted to before a notification could choose its sound.
     *
     * Left in place it would sit in the system's notification settings under the same name as the
     * new default one, doing nothing — two identical entries, one of them dead. Anything the user
     * had set on it is gone either way: a channel's sound is fixed at creation, which is why there
     * is now one per sound, and the old channel cannot be turned into any of them.
     *
     * Except for having been switched off, which is carried over: a new channel arrives switched
     * on, so a user who had silenced reminders would be rung again by the update. All five are
     * made here, in the one moment the old channel can still be asked — after which the block is
     * the user's to lift on each, and a channel made later is nobody's business but its own.
     */
    private fun retireTheOldChannel() {
        // A build between the two made one of these and never posts to it now.
        manager.deleteNotificationChannel("$CHANNEL_PREFIX.default")
        val old = manager.getNotificationChannel(CHANNEL_PREFIX) ?: return
        val blocked = old.importance == NotificationManager.IMPORTANCE_NONE
        // Not Default: it means "whatever the app is set to", which the engine has already
        // resolved by the time anything is posted, so a channel for it would never be used.
        for (sound in NotificationSound.appWide.map(ChosenSound::of)) {
            val importance =
                if (blocked) NotificationManager.IMPORTANCE_NONE else importanceFor(sound)
            manager.createNotificationChannel(channelWith(sound, importance))
        }
        manager.deleteNotificationChannel(CHANNEL_PREFIX)
    }

    private companion object {
        const val CHANNEL_PREFIX = "zheduler.tasks"
    }
}

/** The channel a sound of the user's own is posted to, which goes when the sound does. */
internal fun customChannelId(id: String) = "zheduler.tasks.custom.${id.lowercase()}"

/**
 * The sound itself, as something that can be played: `null` for silence.
 *
 * A sound the user added is looked for first. If its copy has gone the choice falls back on what
 * it was stored alongside, which for an added sound is the platform's own — a wrong sound being
 * better than a reminder nobody hears.
 */
internal fun uriFor(context: Context, sound: ChosenSound): Uri? {
    sound.custom?.let { custom -> customSoundUri(context, custom.id)?.let { return it } }
    return when (sound.builtin) {
        NotificationSound.Silent -> null
        NotificationSound.Default, NotificationSound.System ->
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        NotificationSound.Alarm -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        // From `androidApp/src/main/res/raw`, which is a second copy of the tones in
        // `composeApp/src/commonMain/composeResources/files/sounds`: the system process that plays
        // a channel's sound cannot read another module's assets, so Android needs its own. Change
        // one and change the other.
        NotificationSound.Chime, NotificationSound.Bell ->
            Uri.parse("android.resource://${context.packageName}/raw/${sound.builtin.bundledName}")
    }
}

/**
 * An alarm is routed as one, so it is carried by the alarm volume rather than the notification one
 * and is as loud as the user has alarms set to be.
 */
internal fun audioAttributesFor(sound: ChosenSound): AudioAttributes? =
    if (sound.builtin == NotificationSound.Silent && sound.custom == null) null
    else AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(
            if (sound.builtin == NotificationSound.Alarm) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_NOTIFICATION
        )
        .build()
