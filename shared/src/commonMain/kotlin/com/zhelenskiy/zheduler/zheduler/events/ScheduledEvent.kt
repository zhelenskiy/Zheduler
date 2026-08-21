@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Something a task has arranged to happen at a known moment.
 *
 * Events are derived from tasks, never stored: the planner recomputes them from whatever the
 * repository holds now, so editing a task's due date or its rules changes what is coming without
 * anything having to be cancelled. What *is* stored is the watermark of the last moment swept, so
 * that a process which dies between planning and delivery does not deliver twice — see
 * [ScheduleStore].
 */
sealed interface ScheduledEvent {
    val taskId: String

    /** When it falls due. */
    val at: Instant

    /**
     * Identity across runs, so a delivery can be recognised as one already made.
     *
     * Two events with the same key are the same event; anything a user could edit into a
     * different moment is part of it.
     */
    val key: String

    /**
     * The moment this event is *about*, which is not always the moment it falls.
     *
     * A warning falls when it was asked to and is about the deadline it warns of, so how long ago
     * it was asked for says nothing about whether it is still worth giving: told a month ahead of
     * a deadline that is tomorrow, the warning is as useful as it was meant to be. Judging one by
     * its own age silenced every lead time longer than the catch-up window.
     */
    val relevantAt: Instant get() = at

    /**
     * A reminder [lead] ahead of [dueDate].
     *
     * Identified by the deadline it warns about rather than by the moment it falls, because that
     * moment moves: "a day before" is a different instant in a different zone, and a user who
     * changes zone between two runs would otherwise be reminded twice about one deadline. A
     * recurring task still gets a fresh reminder each cycle, since its deadline moves on too.
     */
    data class Reminder(
        override val taskId: String,
        override val at: Instant,
        val dueDate: Instant,
        val lead: RecurrencePeriod,
        val sound: ChosenSound,
    ) : ScheduledEvent {
        override val key: String
            get() = "reminder:$taskId:${dueDate.toEpochMilliseconds()}:${lead.toBriefString()}"

        override val relevantAt: Instant get() = dueDate
    }

    /** The moment a task's deadline passes with the task still open. */
    data class Deadline(
        override val taskId: String,
        override val at: Instant,
    ) : ScheduledEvent {
        override val key: String get() = "deadline:$taskId:${at.toEpochMilliseconds()}"
    }

    /**
     * A recurrence rule coming round, at which point the task resets and is scheduled anew.
     *
     * [ruleIndex] is the rule's position in the task's list, which is also how the repository
     * identifies it.
     */
    data class Occurrence(
        override val taskId: String,
        override val at: Instant,
        val ruleIndex: Int,
    ) : ScheduledEvent {
        override val key: String get() = "occurrence:$taskId:$ruleIndex:${at.toEpochMilliseconds()}"
    }
}
