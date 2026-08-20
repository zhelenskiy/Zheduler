package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.storage.storeOf

actual fun createScheduleStore(): ScheduleStore =
    KStoreScheduleStore(storeOf(key = "schedule_state", default = ScheduleState()))

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
    return EventNotifier { alert -> showNotification(alert.title, alert.body, alert.id) }
}

private fun requestNotificationPermission(): Unit = js(
    """{
        if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
            Notification.requestPermission();
        }
    }"""
)

/**
 * Falls back to the console when the browser has not been given permission, so a reminder that
 * cannot be shown still leaves a trace of having happened.
 */
private fun showNotification(title: String, body: String, tag: String): Unit = js(
    """{
        if (typeof Notification === 'undefined') {
            console.info('Zheduler: ' + title + ' - ' + body);
        } else if (Notification.permission === 'granted') {
            new Notification(title, { body: body, tag: tag });
        } else {
            if (Notification.permission === 'default') Notification.requestPermission();
            console.info('Zheduler: ' + title + ' - ' + body);
        }
    }"""
)
