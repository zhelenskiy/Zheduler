package com.zhelenskiy.zheduler.zheduler.events

import io.github.xxfast.kstore.KStore

/**
 * Where this platform keeps the scheduler's watermark, and where it puts an alert.
 *
 * Both are deliberately outside the engine: the engine decides *what* happens and when, and these
 * decide what that looks like on a phone, a desktop or a browser tab.
 */
expect fun createScheduleStore(): ScheduleStore

expect fun createEventNotifier(): EventNotifier

/** A [ScheduleStore] over kstore, which every platform here already uses for its settings. */
class KStoreScheduleStore(private val store: KStore<ScheduleState>) : ScheduleStore {
    // Starting over beats refusing to run: this is a record of what has already been said, and a
    // copy written by an older version that no longer decodes costs one repeated notification.
    override suspend fun load(): ScheduleState =
        runCatching { store.get() }.getOrNull() ?: ScheduleState()

    override suspend fun save(state: ScheduleState) = store.set(state)
}
