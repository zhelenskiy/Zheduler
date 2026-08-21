@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.InstantSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What the scheduler must remember across a restart.
 *
 * The engine is otherwise stateless — it recomputes every event from the tasks each time it runs —
 * so this is the whole of what a killed process loses if it is not written down.
 */
@Serializable
data class ScheduleState(
    /**
     * The moment up to which events have been dealt with.
     *
     * Everything at or before it has already been delivered or deliberately passed over, so a
     * process starting again knows to pick up from here rather than from the beginning of time.
     */
    @Serializable(with = InstantSerializer::class)
    val sweptTo: Instant? = null,

    /**
     * Keys of events already delivered, against the moment each one was for.
     *
     * This is what stops anything being raised twice — [sweptTo] says only where the last run got
     * to, and a task can be given a deadline that is already behind it. Kept against the moment
     * rather than against whether the event is still planned: a task completed and reopened within
     * the day would otherwise lose its key while the moment was still recent, and be announced all
     * over again.
     */
    val delivered: Map<String, Long> = emptyMap(),

    /**
     * Whether the device was inside each watched area when it was last looked at, by
     * `GeoArea.key`.
     *
     * The whole of what a boundary crossing is made of: a crossing is this answer differing from
     * the one a fresh reading gives, so without it every sweep would see the device standing
     * somewhere and nothing more. An area absent here has never been looked at, which is not the
     * same as one known to be outside — a rule written while standing at home must not read as
     * arriving home. Pruned each run to the areas still watched.
     */
    val insideAreas: Map<String, Boolean> = emptyMap(),
)

/** Where [ScheduleState] is kept between runs. */
interface ScheduleStore {
    suspend fun load(): ScheduleState
    suspend fun save(state: ScheduleState)
}

/** For tests, and for platforms where losing the watermark on exit is not worth a file. */
class InMemoryScheduleStore(initial: ScheduleState = ScheduleState()) : ScheduleStore {
    private val mutex = Mutex()
    private var state = initial

    override suspend fun load(): ScheduleState = mutex.withLock { state }

    override suspend fun save(state: ScheduleState) = mutex.withLock { this.state = state }
}
