@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.RecurrenceCalculator
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.datetime.TimeZone
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Works out what each task has coming, as instants.
 *
 * Pure: it reads tasks and a zone and returns events. Nothing here delivers, stores or advances
 * anything — [ScheduledEventEngine] does that with what this returns, which is what makes the
 * timing testable without a clock, a database or a notification tray.
 */
object EventPlanner {

    /**
     * Every event [task] has, whether past or still ahead.
     *
     * A task that is finished with — Done or Declined — has none: neither its deadline nor its
     * reminders are anything to raise. Its recurrence rules still do, because coming round again
     * is exactly what a rule on a completed task is for.
     */
    fun eventsFor(task: Task, tz: TimeZone, now: Instant): List<ScheduledEvent> = buildList {
        val settled = task.status is TaskStatus.Done || task.status is TaskStatus.Declined

        task.dueDate?.takeUnless { settled }?.let { due ->
            add(ScheduledEvent.Deadline(task.id, due))
            task.notifications.forEach { notification ->
                val at = notification.timeBeforeDeadline.subtractFrom(due, tz)
                // A reminder is a warning; once the deadline itself has gone by there is nothing
                // left to warn about, and the deadline event covers the moment.
                if (at < due) {
                    add(
                        ScheduledEvent.Reminder(
                            taskId = task.id,
                            at = at,
                            dueDate = due,
                            lead = notification.timeBeforeDeadline,
                            sound = notification.sound,
                        )
                    )
                }
            }
        }

        task.recurrenceRules.forEachIndexed { index, (rule, state) ->
            // A rule with nothing left owed is finished whenever it is asked.
            if (rule.termination.maxOccurrences.let { it != null && it <= 0 }) return@forEachIndexed
            val at = occurrenceOf(rule, state, now) ?: return@forEachIndexed
            // Judged on when the occurrence fell, not on when anyone got round to looking. A rule
            // that stopped at half past eleven still owes the occurrence that fell at eleven, and
            // asking whether the rule has ended *now* threw that one away every morning after.
            val endDate = rule.termination.endDate
            if (endDate != null && at > endDate) return@forEachIndexed
            add(ScheduledEvent.Occurrence(task.id, at, index))
        }
    }

    /** [eventsFor] across many tasks, in the order they fall due. */
    fun eventsFor(tasks: List<Task>, tz: TimeZone, now: Instant): List<ScheduledEvent> =
        tasks.flatMap { eventsFor(it, tz, now) }.sortedBy { it.at }

    /**
     * When a rule next comes round.
     *
     * The stored [RecurrenceState.nextOccurrenceDate] is authoritative once a rule has fired, but
     * a rule saved by the editor arrives with an empty state, and a rule whose next occurrence is
     * unknown must not be read as one that is due now — that would fire every new rule the moment
     * it was written. So an empty state is asked of the calculator instead.
     */
    private fun occurrenceOf(rule: RecurrenceRule, state: RecurrenceState, now: Instant): Instant? {
        state.nextOccurrenceDate?.let { return it }
        // A rule with only a status trigger has no moment of its own; it fires when the status
        // changes, which is not something a schedule can wait for.
        if (rule.timeRecurrenceTrigger == null) return null
        return RecurrenceCalculator.calculateNextOccurrence(rule, state, now)
    }
}
