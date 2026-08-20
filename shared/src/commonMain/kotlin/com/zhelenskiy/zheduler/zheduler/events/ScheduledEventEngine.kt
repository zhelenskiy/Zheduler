@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason
import com.zhelenskiy.zheduler.zheduler.RecurrenceTerminationCondition.AfterOccurrences
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceService
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTriggerEvent
import com.zhelenskiy.zheduler.zheduler.StatusChange
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Reacts to the moments tasks have arranged: delivers reminders, marks deadlines, and lets
 * recurrence rules come round and reschedule the task behind them.
 *
 * The engine keeps nothing in memory that matters. Every run recomputes the whole schedule from
 * the repository and the current zone, so a process that is killed between two runs loses only the
 * waiting, and one that comes back — at boot, or when the user opens the app — picks up where the
 * watermark in [store] says the last one left off. That is also why changing zone needs no special
 * handling beyond running again: the reminders are recomputed in the new zone.
 *
 * @param timeZone read afresh on every run rather than captured, because a long-lived process
 *   outlives the zone it started in — a laptop opened in another country, or simply the night the
 *   clocks change.
 * @param onSwept told when the next event falls, after every run. On a platform whose own
 *   scheduler is what wakes this process — Android's — this is what keeps that scheduler in step:
 *   without it the only thing that ever re-books the wake-up is a run of the wake-up itself, so a
 *   task created in the app while one is booked a day out stays unheard until that day is up.
 */
class ScheduledEventEngine(
    private val repository: TaskRepository,
    private val notifier: EventNotifier,
    private val store: ScheduleStore,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val onSwept: (Instant?) -> Unit = {},
) {

    /**
     * One run at a time. The foreground loop and the platform's background worker share this
     * engine, and both are woken for the same moment: interleaved, they read the same watermark,
     * deliver the same events twice, and the slower one's save puts the watermark back.
     */
    private val sweeping = Mutex()

    /** What one run did, and when the next thing is due. */
    data class Sweep(
        val delivered: List<ScheduledEvent>,
        val nextAt: Instant?,
    )

    /**
     * Deal with everything that has fallen due, and report when the next thing does.
     *
     * Safe to call at any time and as often as liked: an event already dealt with is not dealt
     * with twice, and one that is still ahead is left alone.
     */
    suspend fun sweep(): Sweep = sweeping.withLock { sweepOnce() }

    private suspend fun sweepOnce(): Sweep {
        val now = clock.now()
        val tz = timeZone()
        val previous = store.load()
        val tasks = seedUnscheduledRules(allTasks(), now)
        val planned = EventPlanner.eventsFor(tasks, tz, now)

        // A first run has nothing behind it and says nothing about the past, so installing the app
        // does not announce every deadline the tasks ever missed. After that the only limits are
        // the catch-up window and the keys — what stops a moment being raised twice is having its
        // key already, not having swept past it.
        //
        // The watermark used to be the limit, and it swallowed anything the previous sweep could
        // not have seen: a task given a deadline that had already gone, which is an ordinary way
        // to record something you have missed, was filed without a word.
        // No age limit. A deadline missed over a holiday is still missed, and a warning asked for
        // a year ahead is still the warning that was asked for; the keys are what stop either
        // being said twice. Only a first run holds its tongue, so that meeting a database full of
        // old tasks for the first time is not a pile of notifications.
        val firstRun = previous.sweptTo == null
        val due = planned
            .filter { it.at <= now && it.key !in previous.delivered.keys }
            // A task can carry the same reminder twice over; it is still one thing to say.
            .distinctBy { it.key }

        val delivered = mutableListOf<ScheduledEvent>()
        val candidates = mutableListOf<Candidate>()
        var mutated = false
        for (event in due) {
            // Re-read once anything in this run has changed a task. Two occurrences on one task
            // can both fall in a single late sweep, and the first may reset a status the second is
            // waiting for: judged against the task as it was when the run started, a rule that
            // should have stayed armed fired against a status the task no longer had.
            val task = (if (mutated) repository.getTaskById(event.taskId) else tasks.firstOrNull { it.id == event.taskId })
                ?: continue
            when (event) {
                // An alert about something long gone is not worth raising — and not worth
                // remembering either, since it will only age further out of the window.
                is ScheduledEvent.Reminder, is ScheduledEvent.Deadline -> if (!firstRun) {
                    candidates += Candidate(task.id, event.at, event.rank, alertFor(task, event, now))
                    delivered += event
                }

                // Occurrences are caught up however old they are. The window governs what is worth
                // saying, not whether a recurring task still exists: one left alone for a month
                // that was never brought up to date would have an occurrence stuck in the past,
                // and would never come round again.
                //
                // The occurrence is an event in its own right and says so. A recurring task with
                // a deadline has the Deadline event on the same moment and only one of the two is
                // spoken; one without a deadline had nothing at all before this.
                is ScheduledEvent.Occurrence -> if (advance(task, event, now)) {
                    mutated = true
                    delivered += event
                    if (!firstRun) {
                        candidates += Candidate(task.id, event.at, event.rank, alertFor(task, event, now))
                    }
                }
            }
        }

        // From a fresh read: the loop above has just reset statuses, and a rule waiting for one of
        // them should see what the task is now rather than what it was when this run started.
        val settled = if (mutated) allTasks() else tasks
        val statusFired = fireStatusTriggers(settled, now)

        val after = if (mutated || statusFired) allTasks() else tasks
        val replanned = EventPlanner.eventsFor(after, tz, now)
        val nextAt = replanned.filter { it.at > now }.minOfOrNull { it.at }

        val announced = announceAutomaticChanges(now, previous.delivered, firstRun = previous.sweptTo == null) {
            candidates += it
        }

        // Kept while the event they are for is still one the planner would offer. Nothing retires
        // by age any more, so this is what bounds the set: a task that is finished with stops
        // being planned and takes its keys with it.
        val stillPlanned = replanned.mapTo(mutableSetOf()) { it.key }
        store.save(
            ScheduleState(
                sweptTo = now,
                delivered = previous.delivered.filterKeys { it in stillPlanned } +
                    delivered.associate { it.key to it.relevantAt.toEpochMilliseconds() } + announced,
            )
        )
        speak(candidates)
        onSwept(nextAt)
        return Sweep(delivered, nextAt)
    }

    /**
     * Sweep, wait for the next event, sweep again, until cancelled.
     *
     * The wait is capped at [pollInterval] even when the next event is months away: nothing tells
     * this loop that the zone changed, that another window edited a task, or that the machine has
     * just come back from being suspended for two days, so it looks again regularly. An edit that
     * the repository does report cuts the wait short.
     */
    suspend fun run(pollInterval: Duration = 15.minutes) {
        while (currentCoroutineContext().isActive) {
            // One bad run must not end the loop. A single unreadable task or a failed write would
            // otherwise stop every reminder for as long as the app stayed open, silently.
            val sweep = try {
                sweep()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                Sweep(delivered = emptyList(), nextAt = null)
            }
            val untilNext = sweep.nextAt?.minus(clock.now()) ?: pollInterval
            // Floored rather than allowed to reach zero: a moment that stays due however often it
            // is swept would otherwise be a loop with no wait in it at all.
            val wait = minOf(untilNext, pollInterval).coerceAtLeast(1.seconds)
            withTimeoutOrNull(wait) { repository.changes.first() }
        }
    }

    /** Something worth saying about a task, and how much it is worth saying next to the others. */
    private data class Candidate(
        val taskId: String,
        val at: Instant,
        val rank: Int,
        val alert: TaskAlert,
    )

    /**
     * How much a moment is worth saying, when several land on one task at once. A deadline speaks
     * over the warnings that led up to it, and over the reset it caused.
     */
    private val ScheduledEvent.rank: Int
        get() = when (this) {
            is ScheduledEvent.Deadline -> 3
            is ScheduledEvent.Occurrence -> 2
            is ScheduledEvent.Reminder -> 1
        }

    /**
     * One notification per task, however much happened to it.
     *
     * A device that has been off, or a deadline set in the past, brings every warning and the
     * deadline itself due in the same run — four notifications about one task, three of them about
     * a moment that had already gone by the time anyone read them. The latest is the one that says
     * where the task actually stands, so that is the one that is said. In ordinary running each
     * moment arrives in its own run and each is spoken.
     */
    private suspend fun speak(candidates: List<Candidate>) {
        candidates
            .groupBy { it.taskId }
            .values
            .map { forOneTask -> forOneTask.maxWith(compareBy({ it.at }, { it.rank })) }
            .sortedBy { it.at }
            .forEach { notifier.post(it.alert) }
    }

    /**
     * Say so when the app has changed a task's status on its own.
     *
     * These are the changes nobody asked for at the time: a task that stopped being blocked
     * because its last blocker was finished, a parent that followed its subtasks, a rule that came
     * round. The user finds out now rather than the next time they happen to scroll past it.
     *
     * Bounded by the catch-up window, so a device that has been off for a fortnight does not
     * recite a fortnight of bookkeeping on the way back, and deduplicated by key rather than by
     * watermark — see below for why.
     */
    private suspend fun announceAutomaticChanges(
        now: Instant,
        alreadyAnnounced: Map<String, Long>,
        firstRun: Boolean,
        offer: (Candidate) -> Unit,
    ): Map<String, Long> {
        // Read afresh rather than bounded by the watermark: the changes this very run has just
        // caused are stamped by the repository's own clock, a few milliseconds after the [now]
        // this run planned from, and a window ending at [now] would miss every one of them — only
        // to pick them up on the next run, which is where the duplicates came from. The keys are
        // what prevent a repeat, and they hold however the clocks fall.
        val recent = repository.getAutomaticStatusChangesBetween(now - RECENT_CHANGES, clock.now())
            .filter { it.statusChange.automaticChangeReason.isWorthAnnouncing() }

        val keys = recent.associate { it.key to it.statusChange.timestamp.toEpochMilliseconds() }
        // A first run has nothing behind it, exactly as with the alerts: it learns what has
        // already happened without reciting a day of it to someone who has just opened the app.
        // Only what it found, though — a change this very run caused, by catching a recurrence up
        // and unblocking something, is news like any other, and suppressing it loses it for good.
        // At or after, not strictly after: a change this run caused is stamped by the repository's
        // own clock and stored to the millisecond, so it can read back as exactly the instant this
        // run planned from.
        val worthSaying =
            if (firstRun) recent.filter { it.statusChange.timestamp >= now } else recent

        worthSaying.filter { it.key !in alreadyAnnounced.keys }.forEach { (task, change) ->
            offer(
                Candidate(
                    taskId = task.id,
                    at = change.timestamp,
                    rank = 0,
                    alert = TaskAlert(
                        id = change.key(task),
                        taskId = task.id,
                        spaceId = task.spaceId,
                        title = task.title,
                        body = "${reasonText(change.automaticChangeReason)} — now ${change.newStatus.displayName}",
                        at = change.timestamp,
                    ),
                )
            )
        }
        return keys
    }

    /**
     * Every status the app arrived at by itself is worth saying — including a recurrence reset,
     * which is the app changing a task under the user as much as an unblocking is. Where the
     * deadline lands on the same moment only one of the two is spoken; see [speak].
     *
     * A status the user set by hand is not news to them.
     */
    private fun AutomaticChangeReason?.isWorthAnnouncing(): Boolean = this != null

    private val StatusChangeEvent.key: String get() = statusChange.key(task)

    private fun StatusChange.key(task: Task): String = "status:${task.id}:${timestamp.toEpochMilliseconds()}"

    private fun reasonText(reason: AutomaticChangeReason?): String = when (reason) {
        is AutomaticChangeReason.Unblocked -> "No longer blocked"
        is AutomaticChangeReason.UpdatedFromSubtasks -> "Followed its subtasks"
        is AutomaticChangeReason.Recurrence -> "Came round again"
        null -> "Changed"
    }

    /**
     * Fire the rules that wait for a status rather than for a moment — "when I mark this done, put
     * it back on the list".
     *
     * These have no place in the plan, because they have no time: what they wait for is the task
     * arriving in one of the statuses they name, which a sweep can only notice by looking. Nothing
     * has to be remembered to keep them from firing twice, since firing moves the task out of the
     * status that triggered it, and a rule whose reset status is one it also waits for is refused
     * by the repository.
     *
     * Only rules with no time trigger are looked for here; one that has both is a moment in the
     * plan like any other, and goes through [advance].
     */
    private suspend fun fireStatusTriggers(tasks: List<Task>, now: Instant): Boolean {
        var fired = false
        for (task in tasks) {
            val waiting = task.recurrenceRules.any { (rule, _) ->
                rule.timeRecurrenceTrigger == null &&
                    // By kind, not by value, exactly as RecurrenceCalculator.shouldTrigger does:
                    // the rule editor stores a bare instance per chip — Blocked with no blockers,
                    // Declined with no reason — which no real task ever equals. Comparing whole
                    // values here left those two chips unable to fire, without the repository's
                    // own correct check ever being reached.
                    rule.statusChangeTrigger?.requiredStatuses.orEmpty()
                        .any { it::class == task.status::class } &&
                    !rule.isTerminated(now)
            }
            if (!waiting) continue
            if (repository.processRecurrenceTrigger(task.id, RecurrenceTriggerEvent(task.status, now)) != null) {
                fired = true
            }
        }
        return fired
    }

    /**
     * Fire a rule, roll its schedule up to the present, and move the task's deadline with it.
     *
     * Three things have to happen and only the first is the repository's:
     *
     * The rule fires once — the task resets to the status the rule names and the rule's own
     * bookkeeping advances. It fires *once* however long the run has been away: a daily task
     * missed for a week is one task to do today, not seven notifications.
     *
     * Its next occurrence is then wound forward past [now]. The repository leaves it one step on
     * from the occurrence that fired, which for a week-old miss is still six days in the past —
     * and an occurrence in the past is one the planner offers again on every run, so without this
     * the task would either repeat in a loop or, once the delivery was remembered, never again.
     *
     * Finally the deadline follows the schedule, because a due date is the task's own field and
     * the repository does not touch it. A task with no deadline keeps none.
     */
    private suspend fun advance(task: Task, event: ScheduledEvent.Occurrence, now: Instant): Boolean {
        // A rule that names a status as well as a moment wants both. The moment arriving first is
        // the rule becoming ready, not the rule going off: left armed, it fires whenever the status
        // turns up. Rolling the schedule past it instead spent an occurrence for something that
        // never happened, and for a rule with a single occurrence killed it outright.
        if (!task.recurrenceRules.getOrNull(event.ruleIndex)?.first.acceptsStatusOf(task)) return false

        // Stamped with the moment the rule was *due*, not the moment this run noticed, so a late
        // sweep does not drag the whole series later with it.
        //
        // Declined when the task is already in the status the rule resets to — the ordinary state
        // of anything that came round yesterday and has not been touched since. The occurrence
        // still happened, so the schedule still moves; only the status has nothing to change to.
        val fired = repository.processRecurrenceTrigger(
            task.id,
            RecurrenceTriggerEvent(task.status, event.at),
        )
        val current = fired ?: repository.getTaskById(task.id) ?: return false

        val rules = current.recurrenceRules
        val (rule, state) = rules.getOrNull(event.ruleIndex) ?: return fired != null

        // Already dealt with. Firing one rule fires every other rule on the task whose moment has
        // also come — the repository cascades — so an occurrence can arrive here with its own rule
        // already wound on. The call above then declines, which is indistinguishable from the
        // ordinary "nothing to reset" decline, and charging it again would spend two of a
        // "stop after N" allowance for one moment. The winding on still happens below: the cascade
        // moves a rule one step, which after a long absence leaves it in the past.
        val alreadyDealtWith = fired == null && state.nextOccurrenceDate.let { it != null && it > event.at }

        // A rule saved by the editor carries no next occurrence of its own; this run worked out
        // the one that just passed, and winding on from anywhere else would lose the cadence.
        val seeded = state.nextOccurrenceDate?.let { state } ?: state.copy(nextOccurrenceDate = event.at)
        val caughtUp = rollForward(rule, seeded, now)
        // The repository spends one of a "stop after N times" allowance when it fires. When it
        // declines — the task already being in the status the rule resets to, which for a daily
        // rule is most days — the occurrence still happened and still counts, or a rule set to
        // stop after three would go on for ever.
        val spent = if (fired == null && !alreadyDealtWith) rule.spendOneOccurrence() else rule

        // A rule with nothing left owed keeps no next occurrence. The allowance is spent after the
        // next date is worked out — by the repository on the way in, and here on the declined path
        // — so the last occurrence of a series left a date behind it that the rule did not owe and
        // the planner rightly ignored. The deadline was moved onto it all the same, and the task
        // announced itself due once more than the rule was ever set to run.
        val settled = if (spent.hasNothingLeft()) caughtUp.copy(nextOccurrenceDate = null) else caughtUp
        val updatedRules = rules.set(event.ruleIndex, spent to settled)
        // The soonest across every rule that is still running, the way initializeRecurrence picks
        // the first one. Taking this rule's own answer let two rules coming round in the same run
        // leave the deadline on whichever happened to be last in the list rather than on next.
        val soonest = updatedRules
            .filterNot { (rule, _) -> rule.hasNothingLeft() }
            .mapNotNull { (_, ruleState) -> ruleState.nextOccurrenceDate }
            .minOrNull()
        val rescheduled = current.copy(
            recurrenceRules = updatedRules,
            dueDate = soonest.takeIf { current.dueDate != null } ?: current.dueDate,
        )
        val moved = rescheduled != current
        // A write that did not happen is not a schedule that moved: reporting it as dealt with
        // would remember the occurrence as done and leave the rule where it was.
        val saved = !moved || repository.updateTask(rescheduled) != null
        return fired != null || alreadyDealtWith || (moved && saved)
    }

    /**
     * The rule's state with its next occurrence moved to the first one that is still ahead.
     *
     * The count is raised to one and then left alone. It has to reach one, because a timeout rule
     * still on zero is read as never having been seeded and answers `firstOccurrence` to every
     * question — so a rule whose very first occurrence passed without firing (an ordinary thing:
     * the task was already in the status the rule resets to) could never be wound on, and stayed
     * stuck on that first date for good. It is not raised per step, so occurrences that were slept
     * through do not eat into a "stop after N times" allowance.
     *
     * A rule that stops making progress — a termination date reached, or a period that computes an
     * occurrence no later than the one before it — is returned as it stands rather than looped on.
     */
    private fun rollForward(rule: RecurrenceRule, state: RecurrenceState, now: Instant): RecurrenceState {
        var current = state
        repeat(MAX_CATCH_UP_STEPS) {
            val from = current.nextOccurrenceDate ?: return current
            if (from > now) return current

            val stepped = current.copy(
                lastOccurrenceDate = from,
                occurrenceCount = maxOf(current.occurrenceCount, 1),
            )
            val next = RecurrenceService.calculateNextOccurrence(rule, stepped, from)
            // Nothing further to come: a rule with no period is a single occurrence, and an end
            // date reached is the same answer. Recorded as having no next occurrence, which is how
            // a spent rule is told from a waiting one — left pointing at the moment that has gone,
            // it was offered again on every run and reset the task the next time its status moved.
            if (next == null || next <= from) return stepped.copy(nextOccurrenceDate = null)
            current = stepped.copy(nextOccurrenceDate = next)
        }
        return current
    }

    /**
     * Every rule of [tasks] given the next occurrence it does not yet have, written down.
     *
     * A rule arrives from the editor with an empty state, and an empty state is read as "not
     * seeded yet" — which the selection in `processRecurrence` treats as a candidate beside
     * whichever rule is actually due. On a task with two rules that meant firing the one that was
     * not due: it lost the start date it was given and was re-anchored to today for good. Filling
     * the date in is what tells the two apart.
     */
    private suspend fun seedUnscheduledRules(tasks: List<Task>, now: Instant): List<Task> =
        tasks.map { task ->
            val rules = task.recurrenceRules
            val seeded = rules.map { (rule, state) -> seed(rule, state, now) }.toPersistentList()

            if (seeded == rules) return@map task
            // Re-read, and seed what is there now rather than writing back the snapshot this run
            // started from: a rule the user edited a moment ago should survive being seeded, as
            // should the rest of the task.
            val latest = repository.getTaskById(task.id) ?: return@map task
            val seededLatest = latest.recurrenceRules.map { (rule, state) -> seed(rule, state, now) }
                .toPersistentList()
            if (seededLatest == latest.recurrenceRules) return@map latest
            repository.updateTask(latest.copy(recurrenceRules = seededLatest)) ?: latest
        }

    /** The rule's state with the next occurrence it lacks, or unchanged if it needs none. */
    private fun seed(rule: RecurrenceRule, state: RecurrenceState, now: Instant): Pair<RecurrenceRule, RecurrenceState> {
        if (rule.timeRecurrenceTrigger == null || state.nextOccurrenceDate != null) return rule to state
        val next = RecurrenceService.calculateNextOccurrence(rule, state, now) ?: return rule to state
        return rule to state.copy(nextOccurrenceDate = next)
    }

    /**
     * Whether this rule's status condition, if it has one, is satisfied by [task] as it stands.
     *
     * By kind, not by value, for the same reason as everywhere else: the editor stores a bare
     * `Blocked` and a bare `Declined` that no real task equals. A rule with no status condition
     * accepts anything, as does a missing rule — the caller has nothing to hold back.
     */
    private fun RecurrenceRule?.acceptsStatusOf(task: Task): Boolean {
        val required = this?.statusChangeTrigger?.requiredStatuses ?: return true
        return required.any { it::class == task.status::class }
    }

    /** Whether this rule's "stop after N times" allowance is used up. */
    private fun RecurrenceRule.hasNothingLeft(): Boolean =
        termination.maxOccurrences.let { it != null && it <= 0 }

    private fun RecurrenceRule.spendOneOccurrence(): RecurrenceRule {
        val remaining = termination.afterOccurrences?.count ?: return this
        return copy(
            termination = termination.copy(
                afterOccurrences = AfterOccurrences((remaining - 1).coerceAtLeast(0)),
            )
        )
    }

    /**
     * What to say about [event], as things stand at [now].
     *
     * Worked out from the clock rather than from the reminder's own setting. A warning set for an
     * hour ahead does not still say "Due in 1 hour" when it is read three hours late — by then the
     * deadline has been and gone, and saying how long is left is worse than saying nothing.
     */
    private fun alertFor(task: Task, event: ScheduledEvent, now: Instant): TaskAlert = TaskAlert(
        id = event.key,
        taskId = task.id,
        spaceId = task.spaceId,
        title = task.title,
        body = when (event) {
            is ScheduledEvent.Reminder -> standingOf(event.dueDate, now)
            is ScheduledEvent.Deadline -> standingOf(event.at, now)
            is ScheduledEvent.Occurrence -> "Came round again"
        },
        at = event.at,
    )

    /** How a deadline stands against the clock: still ahead, right now, or already gone. */
    private fun standingOf(dueDate: Instant, now: Instant): String {
        val remaining = dueDate - now
        return when {
            remaining >= 1.minutes -> "Due in ${remaining.spelledOut()}"
            remaining > -(1.minutes) -> "Due now"
            else -> "Overdue by ${(-remaining).spelledOut()}"
        }
    }

    /**
     * A duration as a person would say it, to one unit — "3 days", "2 hours", "5 minutes".
     *
     * One unit rather than all of them: this goes in a notification, which is read at a glance and
     * where "2 hours" tells you everything "2 hours 13 minutes 4 seconds" does.
     */
    private fun Duration.spelledOut(): String {
        fun plural(count: Long, unit: String) = "$count $unit" + if (count == 1L) "" else "s"
        // Rounded, not truncated: a deadline twenty-three hours and fifty-nine minutes away is
        // tomorrow, and saying "23 hours" for it reads as though it were sooner than it is.
        val minutes = (inWholeSeconds + 30) / 60
        val hours = (minutes + 30) / 60
        val days = (hours + 12) / 24
        return when {
            hours >= 24 -> plural(days, "day")
            minutes >= 60 -> plural(hours, "hour")
            else -> plural(minutes.coerceAtLeast(1), "minute")
        }
    }

    private suspend fun allTasks(): List<Task> =
        repository.getAllSpaces().flatMap { repository.getAllTasks(it.id) }

    private companion object {
        /**
         * How far back the bookkeeping is recited. Deadlines have no such limit, but the statuses
         * the app moved around three weeks ago are not news the way a missed deadline is.
         */
        val RECENT_CHANGES = 1.days

        /**
         * Enough steps to wind even a minutely rule through a couple of months away in one go, and
         * still a backstop against a period that never gets ahead of the clock. Stopping short is
         * not neutral: the rule is left in the past, so the next run fires it again and charges it
         * again, which is exactly what winding forward exists to prevent.
         */
        const val MAX_CATCH_UP_STEPS = 100_000
    }
}
