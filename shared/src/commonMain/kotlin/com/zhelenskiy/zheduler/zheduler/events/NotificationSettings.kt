package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.serialization.Serializable

/**
 * What the app sounds like, for each of the three things it has to say.
 *
 * Split because they are not equally welcome: a deadline arriving is worth a sound a person will
 * look up for, a reminder is a nudge, and the app noting a rule came round or a status followed
 * its subtasks is barely worth hearing. A reminder or a task may still ask for something of its
 * own; these are what everything that does not asks for.
 *
 * A copy written before the split named one sound for all three; [normalized] is what turns one
 * of those into this.
 */
@Serializable
data class NotificationSettings(
    val reminders: ChosenSound = Unconfigured,
    val dueTime: ChosenSound = Unconfigured,
    val announcements: ChosenSound = Unconfigured,
    /**
     * Every sound the user has added, so a file chosen for one reminder can be picked for another
     * without going looking for it again.
     */
    val library: List<CustomSound> = emptyList(),
    /**
     * The single sound a copy written before the split set for everything.
     *
     * Read only to be carried forward — see [normalized] — and never chosen again, so it leaves
     * the file the first time anything is saved.
     */
    val defaultSound: NotificationSound? = null,
) {
    /**
     * The same settings with whatever an older copy said folded into the three that replaced it.
     *
     * Whatever was chosen is kept: there is only a sound to carry at all if someone opened the
     * menu and picked one, and hearing a chime after an update would be the app overruling them.
     * The old "Default" named the platform's own sound and is carried across as [System], which is
     * what that choice is called now — the renaming is the whole of the change.
     */
    fun normalized(): NotificationSettings {
        val carried = defaultSound ?: return this
        val sound = ChosenSound.of(
            if (carried == NotificationSound.Default) NotificationSound.System else carried
        )
        return copy(
            reminders = sound,
            dueTime = sound,
            announcements = sound,
            defaultSound = null,
        )
    }

    /** The three of them, in the order they are shown. */
    fun forRole(role: SoundRole): ChosenSound = when (role) {
        SoundRole.DueTime -> dueTime
        SoundRole.Reminders -> reminders
        SoundRole.Announcements -> announcements
    }

    fun with(role: SoundRole, sound: ChosenSound): NotificationSettings = when (role) {
        SoundRole.DueTime -> copy(dueTime = sound)
        SoundRole.Reminders -> copy(reminders = sound)
        SoundRole.Announcements -> copy(announcements = sound)
    }

    companion object {
        private val Unconfigured = ChosenSound(NotificationSound.Unconfigured)
    }
}

/**
 * The three kinds of notification the app sends, as far as choosing a sound for them goes.
 *
 * Each carries the words it is shown by, since which of the three a notification belongs to is
 * decided by what happened rather than by anything the user set.
 */
enum class SoundRole {
    DueTime,
    Reminders,
    Announcements;

    val displayName: String
        get() = when (this) {
            DueTime -> "When a task is due"
            Reminders -> "Before a task is due"
            Announcements -> "Repeats and automatic status changes"
        }

    val explanation: String
        get() = when (this) {
            DueTime -> "The moment a deadline arrives"
            Reminders -> "Each reminder you set — an hour before, a day before, and so on"
            Announcements -> "A repeating task coming round again, or the app changing a status by itself"
        }
}
