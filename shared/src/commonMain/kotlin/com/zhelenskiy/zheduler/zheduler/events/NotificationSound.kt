package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.serialization.Serializable

/**
 * What a notification should sound like, of the sounds everyone has.
 *
 * A shared vocabulary rather than a file name, because no two platforms own the same sounds and
 * not all of them let an app choose at all. Each entry says what is *meant*; every platform
 * answers it with the nearest thing it has. A sound of the user's own is a [CustomSound] instead.
 *
 * The names are written into every task's `notificationsJson`, so renaming one orphans what is
 * already stored — see `StoredEnumNamesTest`.
 */
@Serializable
enum class NotificationSound {
    /**
     * No sound of its own: whatever the app is set to.
     *
     * Only a reminder or a task's own due time can be left at this — the app's settings are what
     * it defers to, so they cannot defer to themselves.
     */
    Default,

    /** Whatever this platform ordinarily announces a notification with. */
    System,

    /** Seen and not heard. */
    Silent,

    /** The platform's alarm tone: longer and harder to ignore than a notification. */
    Alarm,

    /** A short, soft two-note tone, shipped with the app so it is the same wherever it plays. */
    Chime,

    /** A single struck bell, shipped with the app. */
    Bell;

    /**
     * Whether this tone travels with the app rather than belonging to the platform.
     *
     * The two kinds are reached differently — a platform sound by naming it, a bundled one by
     * pointing at the file — and a platform that cannot play a bundled file falls back to
     * [System] rather than going silent.
     */
    val isBundled: Boolean get() = this == Chime || this == Bell

    /** The file this tone ships as, without extension; `null` for the platform's own sounds. */
    val bundledName: String? get() = if (isBundled) name.lowercase() else null

    companion object {
        /**
         * What the app sounds like before anyone has said otherwise.
         *
         * One of the app's own rather than the platform's: a sound that is the same on every
         * device is a better first impression than one that is whatever this phone happens to use,
         * and "the default" naming a sound nobody chose was the part people found confusing.
         */
        val Unconfigured: NotificationSound = Chime

        /** What may be chosen for the app itself: everything except deferring to itself. */
        val appWide: List<NotificationSound> = entries - Default
    }
}

/**
 * A sound the user brought, kept as a copy of their file under [id].
 *
 * The copy is the point: a file chosen from a downloads folder and tidied away a week later would
 * otherwise leave a reminder silent. [label] is what the file was called when it was chosen, and
 * is only ever shown.
 */
@Serializable
data class CustomSound(
    val id: String,
    val label: String,
)

/**
 * A sound as chosen: one of the shared vocabulary, or a file of the user's own.
 *
 * [custom] wins where it is set, and [builtin] is what remains if the copy has since gone missing.
 */
@Serializable
data class ChosenSound(
    val builtin: NotificationSound = NotificationSound.Default,
    val custom: CustomSound? = null,
) {
    val isDeferred: Boolean get() = custom == null && builtin == NotificationSound.Default

    companion object {
        val Deferred = ChosenSound(NotificationSound.Default)

        fun of(sound: NotificationSound) = ChosenSound(sound)
        fun of(custom: CustomSound) = ChosenSound(NotificationSound.System, custom)
    }
}
