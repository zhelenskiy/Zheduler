package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.serialization.Serializable

/**
 * What a notification should sound like.
 *
 * A shared vocabulary rather than a file name, because no two platforms own the same sounds and
 * not all of them let an app choose at all. Each entry says what is *meant*; every platform
 * answers it with the nearest thing it has.
 *
 * The names are written into every task's `notificationsJson`, so renaming one orphans what is
 * already stored — see `StoredEnumNamesTest`.
 */
@Serializable
enum class NotificationSound {
    /**
     * No sound of its own: a reminder left at this sounds like the rest of the app, and the app
     * left at this sounds like whatever the platform ordinarily announces a notification with.
     */
    Default,

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
     * [Default] rather than going silent.
     */
    val isBundled: Boolean get() = this == Chime || this == Bell

    /** The file this tone ships as, without extension; `null` for the platform's own sounds. */
    val bundledName: String? get() = if (isBundled) name.lowercase() else null
}
