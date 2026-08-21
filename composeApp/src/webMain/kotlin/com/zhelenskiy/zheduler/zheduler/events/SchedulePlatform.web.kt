package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf

actual fun createScheduleStore(): ScheduleStore =
    KStoreScheduleStore(storeOf(key = "schedule_state", default = ScheduleState()))

actual fun createNotificationSettingsStore(): KStore<NotificationSettings> =
    storeOf(key = "notification_settings", default = NotificationSettings())

/**
 * The browser's notification API, which only answers while the tab is open.
 *
 * A page is not a service: nothing here runs after the tab is closed, so the web build catches up
 * on whatever fell due while it was away the next time it is opened, rather than announcing things
 * on time.
 *
 * Permission is asked for when the app starts, not when the first reminder falls due. Asking at the
 * moment of delivery cannot work — the answer comes back asynchronously, so the reminder that
 * prompted the question is always past the `granted` check by the time there is an answer, and it
 * was dropped in silence along with every other one until the user happened to grant it.
 */
actual fun createEventNotifier(): EventNotifier {
    requestNotificationPermission()
    return EventNotifier { alert ->
        // A browser notification has no sound of its own to choose, so a tone the user picked is
        // played by the page. Only the app's own tones can be: the platform sounds are the
        // browser's, and it does not lend them out.
        val tone = if (alert.sound.isBundled) bundledToneUri(alert.sound).orEmpty() else ""
        showNotification(
            title = alert.title,
            body = alert.body,
            tag = alert.id,
            tone = tone,
            silentWithoutTone = alert.sound == NotificationSound.Silent,
        )
    }
}

private fun requestNotificationPermission(): Unit = js(
    """{
        if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
            Notification.requestPermission();
        }
    }"""
)

/**
 * Shows the notification, with a tone of the app's own where one was asked for.
 *
 * The tone goes first because whether it plays decides whether the notification should make a
 * sound of its own: a page the user has never interacted with is refused audio, and a silent
 * notification with nothing behind it would be quieter than asking for no particular sound at all.
 *
 * Falls back to the console when the page has no permission — several browsers only offer the
 * prompt in response to something the user did, so a tab can run for a long time without one, and
 * a reminder that cannot be shown should still leave a trace of having happened.
 */
private fun showNotification(
    title: String,
    body: String,
    tag: String,
    tone: String,
    silentWithoutTone: Boolean,
): Unit = js(
    """{
        if (typeof Notification === 'undefined' || Notification.permission !== 'granted') {
            // No tone either: a chime out of a page showing nothing is a noise from nowhere.
            if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
                Notification.requestPermission();
            }
            console.info('Zheduler: ' + title + ' - ' + body);
            return;
        }
        var show = function (silent) {
            try {
                new Notification(title, { body: body, tag: tag, silent: silent });
            } catch (e) {
                // Some browsers grant the permission and then refuse the constructor, asking to be
                // gone through a service worker instead. Thrown from here it would come back out
                // of the notifier, and the engine has already written the alert down as delivered.
                console.info('Zheduler: ' + title + ' - ' + body);
            }
        };
        if (!tone) {
            show(silentWithoutTone);
            return;
        }
        try {
            var played = new Audio(tone).play();
            if (played && played.then) {
                played.then(function () { show(true); }, function () { show(false); });
            } else {
                show(true);
            }
        } catch (e) {
            show(false);
        }
    }"""
)
