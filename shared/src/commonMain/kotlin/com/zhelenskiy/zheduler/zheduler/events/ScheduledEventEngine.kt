@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason
import com.zhelenskiy.zheduler.zheduler.RecurrenceTerminationCondition.AfterOccurrences
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceService
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.RecurrenceTriggerEvent
import com.zhelenskiy.zheduler.zheduler.StatusChange
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.Geofencing
import com.zhelenskiy.zheduler.zheduler.geo.LocationSource
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignals
import com.zhelenskiy.zheduler.zheduler.geo.NoLocationSource
import com.zhelenskiy.zheduler.zheduler.geo.NoSignalSource
import com.zhelenskiy.zheduler.zheduler.geo.PlaceReading
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import com.zhelenskiy.zheduler.zheduler.geo.SignalReading
import com.zhelenskiy.zheduler.zheduler.geo.SignalSource
import com.zhelenskiy.zheduler.zheduler.geo.satisfiedBy
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
 * @param appSounds what the app sounds like when nothing has asked for anything else. Read afresh
 *   for the same reason as the zone: the user can change it while the process is running. A
 *   reminder, and a task's own deadline, may each bring a choice that overrides it.
 * @param onSwept told when the next event falls, after every run. On a platform whose own
 *   scheduler is what wakes this process — Android's — this is what keeps that scheduler in step:
 *   without it the only thing that ever re-books the wake-up is a run of the wake-up itself, so a
 *   task created in the app while one is booked a day out stays unheard until that day is up.
 * @param locationSource where the device is, asked at most once a run and only when some rule is
 *   watching a place. Defaults to knowing nothing, which is what a platform without positioning
 *   answers and what leaves every rule that waits on a place quietly unfired.
 * @param signalSource what is near the device other than by coordinates — the wifi it is on, the
 *   bluetooth it is connected to. Asked on the same terms as [locationSource] and answering the
 *   same way when it cannot say, per kind: a desktop that knows its network and nothing of
 *   bluetooth leaves the bluetooth rules unanswered rather than reporting them all gone.
 * @param onWatchingPlaces told after every run whether any rule is still waiting on a place. A
 *   platform that can watch continuously — Android, with a foreground service — uses this to start
 *   and stop doing so, because a device that watches where it is going costs battery and should
 *   only do it while something is actually waiting. Sweeping alone samples: a user who goes out and
 *   comes back between two sweeps crossed a boundary twice and the sweep sees neither.
 */
class ScheduledEventEngine(
    private val repository: TaskRepository,
    private val notifier: EventNotifier,
    private val store: ScheduleStore,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val appSounds: () -> NotificationSettings = { NotificationSettings() },
    private val onSwept: (Instant?) -> Unit = {},
    private val locationSource: LocationSource = NoLocationSource,
    private val signalSource: SignalSource = NoSignalSource,
    private val onWatchingPlaces: (WatchNeeds) -> Unit = {},
) {

    /**
     * One run at a time. The foreground loop and the platform's background worker share this
     * engine, and both are woken for the same moment: interleaved, they read the same watermark,
     * deliver the same events twice, and the slower one's save puts the watermark back.
     */
    private val sweeping = Mutex()

    /**
     * What still has to be watched for, after a run.
     *
     * The two apart rather than one flag, because the platforms need different things for each and
     * can hold one without the other: a phone allowed bluetooth but not location can watch for a
     * car and not for a place, and a watch it cannot honour is a notification the user pays for
     * and hears nothing from.
     */
    data class WatchNeeds(
        val places: Boolean = false,
        val signals: Set<SignalKind> = emptySet(),
        /**
         * The radius of the smallest area being watched, or null where none is.
         *
         * Passed on so a platform can ask for fixes at a rate the smallest fence needs. A watch
         * tuned for a kilometre is delivered a fix every minute and only after a hundred metres of
         * movement, which never reports a crossing of a fence eight metres across at walking pace:
         * the user is in and out again between two updates. What the tightest fence costs is what
         * the user asked for by drawing it.
         */
        val tightestMeters: Double? = null,
        /**
         * How far the device was from the nearest watched edge when it was last looked at.
         *
         * What lets the rate follow the user rather than the clock: a phone in a city on the other
         * side of the country from every fence it watches need not be asked every few seconds, and
         * one a street away must be. Null where nothing was measured, which asks for the ordinary
         * rate rather than the cheapest — not knowing is not the same as being far away.
         */
        val nearestMeters: Double? = null,
    ) {
        val any: Boolean get() = places || signals.isNotEmpty()
    }

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

        // Asked once per run and shared by everything below, so a sweep costs at most one fix.
        // Rules fired by a moment or a status are asked only where the device *is* — see
        // PlaceReading.standing — while the crossings themselves fire their own rules further down.
        val watched = watchedAreas(tasks, now)
        val watchedSignals = watchedSignals(tasks, now)
        val surroundings = readSurroundings(watched, watchedSignals, previous, now)
        val reading = surroundings.reading
        val standing = reading.standing()

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
                is ScheduledEvent.Occurrence -> if (advance(task, event, now, standing)) {
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
        val statusFired = fireStatusTriggers(settled, now, standing)

        // After the status rules, and from a fresh read for the same reason they are: a rule that
        // waits for a place and a status wants the status the task has now.
        val crossed = if (statusFired) allTasks() else settled
        val locationFired = fireLocationTriggers(crossed, now, reading)

        val after = if (mutated || statusFired || locationFired) allTasks() else tasks
        val replanned = EventPlanner.eventsFor(after, tz, now)
        val plannedNext = replanned.filter { it.at > now }.minOfOrNull { it.at }

        val announced = announceAutomaticChanges(now, previous.delivered, firstRun = previous.sweptTo == null) {
            candidates += it
        }

        // Kept while the event they are for is still one the planner would offer. Nothing retires
        // by age any more, so this is what bounds the set: a task that is finished with stops
        // being planned and takes its keys with it.
        val stillPlanned = replanned.mapTo(mutableSetOf()) { it.key }
        // Once this run's rules have had their turn, so a rule that has just run out takes its
        // whereabouts with it and stops the device being watched on its behalf.
        val stillWatched = watchedAreas(after, now)
        val stillWatchedSignals = watchedSignals(after, now)
        val watchedKeys = stillWatched.map { it.key } + stillWatchedSignals.map { it.key }
        val insideNow = Geofencing.remember(previous.insideAreas, reading, watchedKeys)
        // Only for what is still watched. The moment itself is settled by the reading — first
        // noticed missing, and left alone on every sweep after that, or the grace would renew
        // itself and a departure would never be noticed at all.
        //
        // What this run could not look at is carried over rather than dropped: a bluetooth stack
        // that wedges for ten minutes would otherwise wipe the note and restart the grace from
        // scratch afterwards, so a car that disconnected half an hour ago would be held as still
        // present for another two minutes every time.
        val missingSinceNow = (
            previous.signalsMissingSince.filterKeys { it !in reading.measured } +
                surroundings.missingSince
            ).filterKeys { key -> stillWatchedSignals.any { it.key == key } }
        store.save(
            ScheduleState(
                sweptTo = now,
                delivered = previous.delivered.filterKeys { it in stillPlanned } +
                    delivered.associate { it.key to it.relevantAt.toEpochMilliseconds() } + announced,
                insideAreas = insideNow,
                signalsMissingSince = missingSinceNow,
            )
        )
        // A signal being held through its grace is a moment of its own: nothing else will happen
        // to make the departure noticed, so unless a sweep is booked for when the grace runs out,
        // a phone that goes still after a disconnection waits for whatever comes next — which with
        // no timed tasks at all is a day away.
        //
        // Worked out from what has just been written down rather than from this run's reading, so
        // that a sweep which could not look at the radios still re-books the one that can. Read
        // from the reading alone, a wedged bluetooth stack would swallow the appointment as well
        // as the answer.
        // Per kind, because the graces differ: a device held for twenty seconds and a network for
        // two minutes must each be come back for at their own moment, and the earliest wins.
        // A key whose kind is no longer known is given the longer of the two — coming back late
        // costs a delay, coming back early reports a departure that has not happened yet.
        val kindByKey = stillWatchedSignals.associate { it.key to it.kind }
        val heldUntil = missingSinceNow
            .filterKeys { insideNow[it] == true }
            .map { (key, since) ->
                val grace = kindByKey[key]?.let(Geofencing::graceFor) ?: Geofencing.WIFI_GRACE
                Instant.fromEpochMilliseconds(since) + grace
            }
            .minOrNull()
        val nextAt = listOfNotNull(plannedNext, heldUntil?.takeIf { it > now }).minOrNull()

        speak(candidates)
        onSwept(nextAt)
        onWatchingPlaces(
            WatchNeeds(
                places = stillWatched.isNotEmpty(),
                signals = stillWatchedSignals.mapTo(mutableSetOf()) { it.kind },
                tightestMeters = stillWatched.minOfOrNull { it.radius() },
                nearestMeters = reading.nearestEdgeMeters,
            )
        )
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
                        sound = appSounds().announcements,
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
    private suspend fun fireStatusTriggers(tasks: List<Task>, now: Instant, standing: PlaceReading): Boolean {
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
                    // A rule that names a place or a network as well as a status wants those too.
                    rule.presenceTriggers.satisfiedBy(standing) &&
                    !rule.isTerminated(now)
            }
            if (!waiting) continue
            if (repository.processRecurrenceTrigger(task.id, RecurrenceTriggerEvent(task.status, now, standing)) != null) {
                fired = true
            }
        }
        return fired
    }

    /**
     * Fire the rules waiting for something to come within reach or leave it — a place, a wifi
     * network, a bluetooth device.
     *
     * The counterpart of [fireStatusTriggers], and unplanned for the same reason: nobody can say
     * in advance when a user will walk somewhere, so a crossing is only ever noticed by looking.
     * What stops one firing twice is that a crossing exists only in the difference between this
     * reading and the last, and the last is written down at the end of every run.
     *
     * A rule with a moment of its own is not fired here — its moment is what fires it, with the
     * surroundings as a condition; see [advance].
     */
    private suspend fun fireLocationTriggers(tasks: List<Task>, now: Instant, reading: PlaceReading): Boolean {
        if (!reading.isCrossing) return false
        var fired = false
        for (task in tasks) {
            val waiting = task.recurrenceRules.any { (rule, _) ->
                rule.timeRecurrenceTrigger == null &&
                    rule.presenceTriggers.isNotEmpty() &&
                    rule.presenceTriggers.satisfiedBy(reading) &&
                    rule.statusChangeTrigger?.requiredStatuses.orEmpty()
                        .let { it.isEmpty() || it.any { status -> status::class == task.status::class } } &&
                    !rule.isTerminated(now)
            }
            if (!waiting) continue
            if (repository.processRecurrenceTrigger(task.id, RecurrenceTriggerEvent(task.status, now, reading)) != null) {
                fired = true
            }
        }
        return fired
    }

    /**
     * Every area some rule of [tasks] is still watching, each place once.
     *
     * A rule's *state* can be finished as well as the rule itself. A one-shot with a moment and a
     * place — "at nine on Monday, once I reach the office" — has fired and has no next occurrence,
     * which is how `shouldTrigger` knows it will never fire again; judged on the rule alone its
     * areas stay watched for ever, and on Android that is a permanent notification and a location
     * reading every minute for something that can no longer happen.
     */
    private fun watchedAreas(tasks: List<Task>, now: Instant): List<GeoArea> =
        liveRules(tasks, now).flatMap { rule -> rule.locationTrigger?.areas.orEmpty() }.distinctBy { it.key }

    /**
     * Every wifi network and bluetooth device some rule of [tasks] is still watching.
     *
     * Gathered from the rule's presence conditions rather than from the fields by name, so that a
     * kind added or split off later is watched without this having to be remembered — which it was
     * not, the one time wifi and bluetooth were split apart.
     */
    private fun watchedSignals(tasks: List<Task>, now: Instant): List<NearbySignal> =
        liveRules(tasks, now)
            .flatMap { rule -> rule.presenceTriggers }
            .filterIsInstance<RecurrenceTrigger.NearbyChange>()
            .flatMap { it.signals }
            .distinctBy { it.key }

    /** The rules of [tasks] that can still come round. */
    private fun liveRules(tasks: List<Task>, now: Instant): List<RecurrenceRule> = tasks
        .flatMap { task -> task.recurrenceRules }
        .filterNot { (rule, state) -> rule.isTerminated(now) || rule.hasNothingLeft() || rule.isSpent(state) }
        .map { (rule, _) -> rule }

    /**
     * Whether this rule's schedule has run out, as `RecurrenceCalculator.shouldTrigger` reads it:
     * a rule with a moment of its own that has fired and has no next one.
     */
    private fun RecurrenceRule.isSpent(state: RecurrenceState): Boolean =
        timeRecurrenceTrigger != null && state.nextOccurrenceDate == null && state.occurrenceCount > 0

    /**
     * Everything around the device that some rule cares about, as one answer.
     *
     * Both halves are asked separately and joined, because they come from different hardware and
     * either can fail on its own: a phone in a basement has no fix and can still see the office
     * wifi, and either half being unknown must not silence the other.
     *
     * Nothing at all is asked when no rule is watching: positioning and radios cost battery and,
     * on the phones, a permission prompt, and a database with no such rule in it should never
     * provoke either.
     */
    private suspend fun readSurroundings(
        areas: List<GeoArea>,
        signals: List<NearbySignal>,
        previous: ScheduleState,
        now: Instant,
    ): Surroundings {
        val places = readPlaces(areas, previous.insideAreas)
        val nearby = readSignals(signals, previous, now)
        return Surroundings(
            reading = Geofencing.combine(places, nearby.reading),
            missingSince = nearby.missingSince,
        )
    }

    /**
     * One run's answer about what is around the device, and which signals were *really* there.
     *
     * The two are not the same set. A signal inside its grace period counts as present so that a
     * momentary drop is not a departure — but it was not seen, and the moment the grace is measured
     * from must not be moved on by it. Refreshed from what counts as present rather than from what
     * was seen, a signal that has genuinely gone is held for ever by any device that sweeps more
     * often than the grace is long, which on a phone that is moving is every one of them.
     */
    private data class Surroundings(
        val reading: PlaceReading,
        val missingSince: Map<String, Long>,
    )

    /**
     * Where the device is, against [areas], given [wasInside] from the last run.
     *
     * A source that cannot answer — no permission, no hardware, a desktop — leaves the whereabouts
     * unknown rather than reporting the device outside everything.
     */
    private suspend fun readPlaces(areas: List<GeoArea>, wasInside: Map<String, Boolean>): PlaceReading {
        if (areas.isEmpty()) return PlaceReading.Unknown
        val fix = try {
            locationSource.currentFix()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        } ?: return PlaceReading.Unknown
        return Geofencing.read(areas, fix, wasInside)
    }

    /** What is near the device, against [signals], given what was near it last run. */
    private suspend fun readSignals(
        signals: List<NearbySignal>,
        previous: ScheduleState,
        now: Instant,
    ): SignalReading {
        if (signals.isEmpty()) return SignalReading(PlaceReading.Unknown, emptyMap())
        val nearby = try {
            signalSource.nearby()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            NearbySignals.Unknown
        }
        return Geofencing.readSignals(
            signals = signals,
            nearby = nearby,
            wasInside = previous.insideAreas,
            missingSince = previous.signalsMissingSince,
            now = now,
            grace = Geofencing::graceFor,
        )
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
    private suspend fun advance(
        task: Task,
        event: ScheduledEvent.Occurrence,
        now: Instant,
        standing: PlaceReading,
    ): Boolean {
        // A rule that names a status as well as a moment wants both. The moment arriving first is
        // the rule becoming ready, not the rule going off: left armed, it fires whenever the status
        // turns up. Rolling the schedule past it instead spent an occurrence for something that
        // never happened, and for a rule with a single occurrence killed it outright.
        val armed = task.recurrenceRules.getOrNull(event.ruleIndex)?.first
        if (!armed.acceptsStatusOf(task)) return false
        // And a place or a network named alongside a moment reads the same way: the occurrence
        // waits until the device is there. Asked as a state, not a crossing — "every Monday, if I
        // am at the office" is answered by where the user is on Monday, not by them having just
        // walked in.
        if (armed != null && !armed.presenceTriggers.satisfiedBy(standing)) return false

        // Stamped with the moment the rule was *due*, not the moment this run noticed, so a late
        // sweep does not drag the whole series later with it.
        //
        // Declined when the task is already in the status the rule resets to — the ordinary state
        // of anything that came round yesterday and has not been touched since. The occurrence
        // still happened, so the schedule still moves; only the status has nothing to change to.
        val fired = repository.processRecurrenceTrigger(
            task.id,
            RecurrenceTriggerEvent(task.status, event.at, standing),
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
        // Each of the three asks the app only where nothing nearer to the user has an answer.
        sound = when (event) {
            is ScheduledEvent.Reminder -> event.sound.orTheAppsOwn(SoundRole.Reminders)
            is ScheduledEvent.Deadline -> task.dueSound.orTheAppsOwn(SoundRole.DueTime)
            is ScheduledEvent.Occurrence -> appSounds().announcements
        },
    )

    /**
     * The sound to use for something that named none of its own.
     *
     * A reminder left alone is not one that asked for the platform's ping — it is one whose user
     * never opened the picker, and it should sound like the rest of the app does for its kind.
     */
    private fun ChosenSound.orTheAppsOwn(role: SoundRole): ChosenSound =
        if (isDeferred) appSounds().forRole(role) else this

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
