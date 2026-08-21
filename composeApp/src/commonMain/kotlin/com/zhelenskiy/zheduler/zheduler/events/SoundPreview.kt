package com.zhelenskiy.zheduler.zheduler.events

/**
 * Plays [sound] where the user is choosing it, so the choice can be made by ear.
 *
 * A preview is not a notification: it is the tone alone, played by the app rather than handed to
 * the system, so nothing appears on screen and nothing is recorded as having been delivered. Where
 * a platform will not lend out the sound it uses for notifications, there is nothing to play and
 * the choice stays one made by name.
 *
 * Choosing [NotificationSound.Silent] stops what the last choice started, so that clicking silence
 * is how a person stops a sound as well as how they choose not to have one. What cannot be stopped
 * is what the platform gives no handle on — a desktop clip, a system tone asked for by number —
 * and those last about a second. A sound the platform will keep making for longer than a preview
 * is worth is cut off on its own.
 *
 * Returns as soon as the sound is under way; nothing waits for it to end.
 */
expect suspend fun previewNotificationSound(sound: ChosenSound)
