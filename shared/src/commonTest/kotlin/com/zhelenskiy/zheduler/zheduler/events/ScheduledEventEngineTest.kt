@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTermination
import com.zhelenskiy.zheduler.zheduler.RecurrenceTimeZone
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.TaskNotification
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.RecurrenceTerminationCondition
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoFix
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.GeofenceDirection
import com.zhelenskiy.zheduler.zheduler.geo.Geofencing
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignals
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The engine is what turns a stored due date into something that actually happens. What these
 * tests defend is the part that is easy to get wrong and impossible to notice: that an alert goes
 * out once and not twice, that a process killed and restarted neither repeats itself nor loses the
 * event it was waiting for, and that a recurring task moves on instead of sitting overdue forever.
 */
class ScheduledEventEngineTest {

    private val ny = TimeZone.of("America/New_York")

    private class MutableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
    }

    private class RecordingNotifier : EventNotifier {
        private val mutex = Mutex()
        val alerts = mutableListOf<TaskAlert>()
        override suspend fun post(alert: TaskAlert) {
            mutex.withLock { alerts += alert }
        }
    }

    private class Fixture(
        val repository: TaskRepository,
        val spaceId: String,
        val clock: MutableClock,
        val notifier: RecordingNotifier,
        val store: ScheduleStore,
        var zone: TimeZone,
    ) {
        var onSwept: (Instant?) -> Unit = {}

        /** Told after every run what is still worth watching for. */
        var onWatchingPlaces: (ScheduledEventEngine.WatchNeeds) -> Unit = {}

        /**
         * Where the device is, as the test says it is.
         *
         * Null is a device that cannot say, which is what a desktop answers and what the engine
         * must treat as "unknown" rather than as "outside everything".
         */
        var whereabouts: GeoFix? = null

        /** How many times the platform was actually asked, which is a cost worth pinning. */
        var timesAsked: Int = 0
            private set

        /** What the radios can see, as the test says they can. */
        var nearby: NearbySignals = NearbySignals.Unknown

        /** How many times the radios were read — the same cost, and the same worth pinning. */
        var timesAskedNearby: Int = 0
            private set

        /** A new engine over the same repository and store — what a restarted process gets. */
        fun engine() = ScheduledEventEngine(
            repository = repository,
            notifier = notifier,
            store = store,
            clock = clock,
            timeZone = { zone },
            onSwept = { onSwept(it) },
            onWatchingPlaces = { onWatchingPlaces(it) },
            locationSource = {
                timesAsked++
                whereabouts
            },
            signalSource = {
                timesAskedNearby++
                nearby
            },
        )
    }

    private suspend fun fixture(now: Instant, zone: TimeZone = TimeZone.UTC): Fixture {
        val clock = MutableClock(now)
        val repository = InMemoryTaskRepository(clock)
        val space = assertNotNull(repository.createSpace("Test", "TEST"))
        return Fixture(repository, space.id, clock, RecordingNotifier(), InMemoryScheduleStore(), zone)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, tz: TimeZone = TimeZone.UTC) =
        LocalDateTime(year, month, day, hour, minute).toInstant(tz)

    @Test
    fun `a reminder is delivered once and not again by a restarted process`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Pay rent",
            dueDate = start + 2.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1))),
        )

        f.engine().sweep()
        assertTrue(f.notifier.alerts.isEmpty(), "nothing is due yet")

        f.clock.current = start + 1.hours
        f.engine().sweep()
        assertEquals(1, f.notifier.alerts.size, "the reminder falls due an hour before the deadline")
        assertEquals("Pay rent", f.notifier.alerts.single().title)
        // "Due in hour" is what the recurrence phrasing gives — it drops the one, because it is
        // built for "Every hour". A notification is a sentence and needs the number.
        assertEquals("Due in 1 hour", f.notifier.alerts.single().body)

        // Same clock, fresh engine: the process died and came back before anything else happened.
        f.engine().sweep()
        f.engine().sweep()
        assertEquals(1, f.notifier.alerts.size, "a restart must not repeat a delivered reminder")
    }

    @Test
    fun `a recurring task with no deadline still says when it comes round`() = runTest {
        // The event happening is worth a notification in its own right. A recurring task with no
        // due date has no deadline to speak for it, so nothing was ever said: the task quietly
        // reopened itself and the user found out by looking.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Water the plants",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(
                RecurrenceRule(
                    timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                        period = RecurrencePeriod(days = 1),
                        firstOccurrence = firstOccurrence,
                        timezone = RecurrenceTimeZone.SystemDefault,
                    ),
                    statusChangeTrigger = null,
                    resetToStatus = TaskStatus.Open,
                ) to RecurrenceState()
            ),
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()

        assertEquals(
            1,
            f.notifier.alerts.size,
            "the occurrence is the event; got ${f.notifier.alerts.map { it.body }}",
        )
        assertEquals("Water the plants", f.notifier.alerts.single().title)
        assertEquals(
            // The status it has landed on is the news. "Came round again" named the machinery
            // instead, which tells the reader nothing about what their task now says — and read
            // as a second, differently-worded notice about the same event as the status change.
            "Status changed: ${TaskStatus.Open.displayName}",
            f.notifier.alerts.single().body,
        )
    }

    @Test
    fun `an occurrence that changed nothing does not claim it changed something`() = runTest {
        // The ordinary day of a recurring task's life: it reset yesterday, nobody has touched it,
        // and it is already in the status the rule resets to. The schedule still moves on, which
        // is why this is announced at all — but nothing changed, and saying otherwise is simply
        // untrue about the one fact the notification exists to carry.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Water the plants",
            status = TaskStatus.Open,
            recurrenceRules = persistentListOf(
                RecurrenceRule(
                    timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                        period = RecurrencePeriod(days = 1),
                        firstOccurrence = firstOccurrence,
                    ),
                    statusChangeTrigger = null,
                    resetToStatus = TaskStatus.Open,
                ) to RecurrenceState()
            ),
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()

        val bodies = f.notifier.alerts.map { it.body }
        assertTrue(
            bodies.none { it.startsWith("Status changed") },
            "nothing changed, so nothing should say it did; got $bodies",
        )
        assertTrue(
            bodies.any { it == "Still ${TaskStatus.Open.displayName}" },
            "and it should still say where the task stands; got $bodies",
        )
    }

    @Test
    fun `an occurrence names the status the task has landed on rather than the one it left`() =
        runTest {
            // The task is reset *before* the alert is built, and the copy the loop is holding is
            // the one from before. Named from that, every notification would announce the status
            // the user had already seen — the one thing it is certainly not about.
            val start = at(2026, 6, 1, 9, 0)
            val f = fixture(start)
            val firstOccurrence = start + 1.hours
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Water the plants",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )

            f.engine().sweep()
            f.clock.current = firstOccurrence
            f.engine().sweep()

            val bodies = f.notifier.alerts.map { it.body }
            assertTrue(
                bodies.any { it == "Status changed: ${TaskStatus.Open.displayName}" },
                "it should say what the task is now; got $bodies",
            )
            assertTrue(
                bodies.none { it.contains(TaskStatus.Done.displayName) },
                "and not what it was; got $bodies",
            )
        }

    @Test
    fun `both the warning and the deadline itself are announced`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val due = start + 2.hours
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "File the return",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1))),
        )

        f.engine().sweep()
        f.clock.current = due - 1.hours
        f.engine().sweep()
        f.clock.current = due
        f.engine().sweep()

        assertEquals(
            listOf("Due in 1 hour", "Due now"),
            f.notifier.alerts.map { it.body },
            "the warning when it was asked for, and the deadline when it arrives",
        )
    }

    @Test
    fun `a backlog of missed moments is one notification and not a pile`() = runTest {
        // The device was off, or the deadline was set in the past. Everything the task had to say
        // fell due at once; saying all of it is four notifications about one task.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val due = start + 4.hours
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Renew the passport",
            dueDate = due,
            notifications = persistentListOf(
                TaskNotification(RecurrencePeriod(hours = 3)),
                TaskNotification(RecurrencePeriod(hours = 2)),
                TaskNotification(RecurrencePeriod(hours = 1)),
            ),
        )

        f.engine().sweep()

        // Back an instant after the deadline: three warnings and the deadline are all behind us.
        f.clock.current = due + 1.minutes
        f.engine().sweep()

        assertEquals(
            listOf("Overdue by 1 minute"),
            f.notifier.alerts.map { it.body },
            "one notification, worded from the clock rather than from the warning that raised it",
        )
    }

    @Test
    fun `a deadline that was already past when the task was written is announced`() = runTest {
        // Giving a task a deadline that has gone is an ordinary thing to do — it is how you record
        // something you have already missed. The moment is behind the last sweep, which is what
        // "already dealt with" was being read from, so the task was filed in silence.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)

        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Should have called back",
            dueDate = start - 2.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1))),
        )
        f.engine().sweep()

        assertEquals(
            listOf("Overdue by 2 hours"),
            f.notifier.alerts.map { it.body },
            "one notification, for a deadline the task was born with and has already missed",
        )
    }

    @Test
    fun `a warning set long before the deadline still arrives`() = runTest {
        // "Tell me a month ahead" on a deadline that is tomorrow. The moment to speak was weeks
        // ago, but what it warns about has not happened yet — the warning is exactly as useful as
        // it was meant to be, and judging it by how old the moment is threw it away.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)

        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Renew the passport",
            dueDate = start + 1.days,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(months = 1))),
        )
        f.engine().sweep()

        assertEquals(
            listOf("Due in 1 day"),
            f.notifier.alerts.map { it.body },
            "said as it stands today, not as it was set a month out",
        )
    }

    @Test
    fun `a deadline missed by more than a day is still announced`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val due = start + 1.hours
        f.repository.addTask(f.spaceId, title = "Send the form", dueDate = due)

        f.engine().sweep()

        // Away for three days after it fell due.
        f.clock.current = due + 3.days
        f.engine().sweep()

        assertEquals(
            listOf("Overdue by 3 days"),
            f.notifier.alerts.map { it.body },
            "still overdue, still worth saying",
        )
    }

    @Test
    fun `a first run does not announce deadlines that went by before it`() = runTest {
        // Two hours old, so it is well inside the catch-up window: what suppresses it is the run
        // having nothing behind it, not the window. A deadline old enough for the window to catch
        // would pass this test whether the first-run rule existed or not.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(f.spaceId, title = "Missed this morning", dueDate = start - 2.hours)

        f.engine().sweep()

        assertTrue(
            f.notifier.alerts.isEmpty(),
            "opening the app for the first time is not a reason to announce what it never saw",
        )
    }

    @Test
    fun `a deadline that passed while the process was gone is announced when it returns`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(f.spaceId, title = "Submit form", dueDate = start + 3.hours)

        f.engine().sweep()

        // The process is gone for the moment the deadline passes, and comes back an hour later.
        f.clock.current = start + 4.hours
        f.engine().sweep()

        assertEquals(listOf("Submit form"), f.notifier.alerts.map { it.title })
        // An hour late by the time anyone reads it, and it says so rather than "Due now".
        assertEquals("Overdue by 1 hour", f.notifier.alerts.single().body)
    }

    @Test
    fun `a settled task raises nothing`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Already done",
            status = TaskStatus.Done,
            dueDate = start + 1.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(minutes = 30))),
        )

        f.engine().sweep()
        f.clock.current = start + 2.hours
        f.engine().sweep()

        assertTrue(f.notifier.alerts.isEmpty(), "a finished task has no deadline left to warn about")
    }

    @Test
    fun `a recurring task repeats and its deadline moves to the next occurrence`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Water the plants",
                status = TaskStatus.Done,
                dueDate = firstOccurrence,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(TaskStatus.Open, after.status, "the occurrence resets the task for the next round")
        assertEquals(
            firstOccurrence + 1.days,
            after.dueDate,
            "the deadline moves on, or the task stays overdue forever",
        )
    }

    @Test
    fun `a recurring task keeps repeating when it is left in the status it resets to`() = runTest {
        // The ordinary case for anything daily: it came round yesterday, reset to Open, and the
        // user has not touched it since. The rule engine refuses to reset a task to the status it
        // is already in, so nothing but the schedule moves — but the schedule must still move, or
        // the task is stuck on yesterday's occurrence and never comes round again.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Feed the cat",
                status = TaskStatus.Done,
                dueDate = firstOccurrence,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()
        assertEquals(TaskStatus.Open, assertNotNull(f.repository.getTaskById(task.id)).status)

        // The next day arrives with the task still Open.
        f.clock.current = firstOccurrence + 1.days
        f.engine().sweep()

        assertEquals(
            firstOccurrence + 2.days,
            assertNotNull(f.repository.getTaskById(task.id)).dueDate,
            "the second occurrence moves the deadline on as the first one did",
        )
    }

    @Test
    fun `a recurring task left behind for days catches up in one run`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Daily standup",
                status = TaskStatus.Done,
                dueDate = firstOccurrence,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence + 5.days
        val sweep = f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertNotNull(after.dueDate)
        assertTrue(
            after.dueDate!! > f.clock.current,
            "after catching up the next occurrence is ahead of now, not five days behind",
        )
        assertNotNull(sweep.nextAt, "and there is a next moment to wait for")
        assertTrue(sweep.nextAt!! > f.clock.current)
    }

    @Test
    fun `a rule triggered by a status brings the task back when it reaches that status`() = runTest {
        // "When I mark this done, put it back on the list." The rule has no time of its own — the
        // event it waits for is the status itself.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Restock the fridge",
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "nothing has happened to it yet",
        )

        f.repository.updateTask(assertNotNull(f.repository.getTaskById(task.id)).copy(status = TaskStatus.Done))
        f.clock.current = start + 1.minutes
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "reaching Done is what the rule was waiting for",
        )
    }

    @Test
    fun `a status rule waiting on Declined fires for a real declined task`() = runTest {
        // The rule editor stores a bare chip per status — Declined with no reason — and no real
        // task ever equals it. Matching the whole value rather than the kind left the Blocked and
        // Declined chips unable to fire at all, which is the bug the repository's own check was
        // fixed for; a pre-filter here can reintroduce it without the repository being reached.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Renew the licence",
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(
                            persistentSetOf(TaskStatus.Declined(""))
                        ),
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.repository.updateTask(
            assertNotNull(f.repository.getTaskById(task.id))
                .copy(status = TaskStatus.Declined("not this quarter"))
        )
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "a declined task is declined whatever the reason says",
        )
    }

    @Test
    fun `a rule whose very first occurrence passes without firing still moves on`() = runTest {
        // The commonest shape there is: a daily task created Open with a rule that resets it to
        // Open. The repository declines to reset a status that is already right, so the rule never
        // fires — and a timeout rule that has never fired answers "firstOccurrence" to every
        // question about its next one, so nothing could wind it forward and it stuck there for good.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Check the logs",
                status = TaskStatus.Open,
                dueDate = firstOccurrence,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        val sweep = f.engine().sweep()

        assertEquals(
            firstOccurrence + 1.days,
            assertNotNull(f.repository.getTaskById(task.id)).dueDate,
            "the occurrence passed, so the schedule moves on even though the status had nowhere to go",
        )
        assertEquals(firstOccurrence + 1.days, sweep.nextAt, "and the next moment is known")
    }

    @Test
    fun `a rule that is not due yet does not fire because another rule on the task is`() = runTest {
        // Two rules, and only the second is due. A rule the editor has just saved carries no next
        // occurrence, and the selection in processRecurrence keeps every such rule beside the one
        // that is genuinely due, then takes the lowest index — so the monthly rule fires today,
        // loses the start date it was given, and is re-anchored to now for good.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val monthly = start + 30.days
        val daily = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two rules",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(months = 1),
                            firstOccurrence = monthly,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.InProgress,
                    ) to RecurrenceState(),
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = daily,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState(),
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = daily
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(
            monthly,
            after.recurrenceRules[0].second.nextOccurrenceDate,
            "the monthly rule is still waiting for the date it was given",
        )
        assertEquals(
            TaskStatus.Open,
            after.status,
            "the daily rule is the one that came round, so its reset is the one that took effect",
        )
    }

    @Test
    fun `a rule waiting for a status sees the status the run has just given the task`() = runTest {
        // Two occurrences in one late sweep. The first resets the task to Open; the second belongs
        // to a rule waiting for Done. Judging it against the task as it was when the run started —
        // Done — fired it against a task that is no longer Done, resetting the status again and
        // spending the only occurrence it had. Sweeping promptly would have left it armed, so an
        // overnight gap was the whole difference.
        val start = at(2026, 6, 1, 8, 0)
        val f = fixture(start)
        val nine = at(2026, 6, 1, 9, 0)
        val ten = at(2026, 6, 1, 10, 0)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two rules one sweep",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = nine,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState(),
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = null,
                            firstOccurrence = ten,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(
                            persistentSetOf(TaskStatus.Done)
                        ),
                        resetToStatus = TaskStatus.InProgress,
                        termination = RecurrenceTermination.afterOccurrences(1),
                    ) to RecurrenceState(),
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = at(2026, 6, 1, 11, 0)
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(TaskStatus.Open, after.status, "the daily rule reset it; the waiting rule did not")
        assertEquals(
            1,
            after.recurrenceRules[1].first.termination.maxOccurrences,
            "and the waiting rule still has its one occurrence to give",
        )
    }

    @Test
    fun `a rule that has run out leaves no occurrence behind it`() = runTest {
        // The repository works out the next occurrence before it spends the last of the allowance,
        // so a finished rule was left pointing at a date it no longer owed. Nothing serviced that
        // date — the planner knows the rule is done — but the deadline had been moved onto it, so
        // the task announced itself as due one more time than the rule was ever set to run.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Twice and no more",
                status = TaskStatus.Done,
                dueDate = firstOccurrence,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination.afterOccurrences(2),
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()
        f.clock.current = firstOccurrence + 1.days
        f.engine().sweep()

        val spent = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(0, spent.recurrenceRules.single().first.termination.maxOccurrences)
        assertNull(
            spent.recurrenceRules.single().second.nextOccurrenceDate,
            "a rule with nothing left owed has no next occurrence",
        )

        // The day after the series ended.
        f.clock.current = firstOccurrence + 2.days
        f.engine().sweep()

        assertEquals(
            1,
            f.notifier.alerts.count { it.body == "Due now" },
            "the deadline came round for each occurrence the rule owed and no more",
        )
    }

    @Test
    fun `the last occurrence before an end date still happens if it is noticed late`() = runTest {
        // The occurrence fell at 23:00 and the rule stops at 23:30; the phone was asleep from 22:00
        // until morning. Asking whether the rule has ended *now* rather than whether this
        // occurrence fell before it ended threw the last one away, and every later sweep threw it
        // away again — the reset the user scheduled simply never happened.
        val lastOccurrence = at(2026, 6, 10, 23, 0)
        val endsAt = at(2026, 6, 10, 23, 30)
        val f = fixture(at(2026, 6, 10, 22, 0))
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Nightly until Wednesday",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = lastOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination.onDate(endsAt),
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = at(2026, 6, 11, 7, 0)
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "the occurrence was inside the rule's life even though the sweep was not",
        )
    }

    @Test
    fun `two rules coming round together spend one occurrence each and no more`() = runTest {
        // Firing a rule fires every other rule on the task whose moment has also come — the
        // repository cascades. The second occurrence then arrives here already dealt with, and
        // declining to fire it a second time looks exactly like the ordinary "nothing to reset"
        // decline. Charging it again spends two of an allowance for one moment, so a rule set to
        // stop after five stops after two and a half.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val moment = start + 1.hours
        fun timeout(period: RecurrencePeriod) = RecurrenceTrigger.AfterTimeout(
            period = period,
            firstOccurrence = moment,
            timezone = RecurrenceTimeZone.SystemDefault,
        )
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two at once",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = timeout(RecurrencePeriod(days = 7)),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.InProgress,
                    ) to RecurrenceState(),
                    RecurrenceRule(
                        timeRecurrenceTrigger = timeout(RecurrencePeriod(days = 1)),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination.afterOccurrences(5),
                    ) to RecurrenceState(),
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = moment
        f.engine().sweep()

        assertEquals(
            4,
            assertNotNull(f.repository.getTaskById(task.id))
                .recurrenceRules[1].first.termination.maxOccurrences,
            "one moment costs one of the five",
        )
    }

    @Test
    fun `a rule needing both a moment and a status waits for the status`() = runTest {
        // "At June 1, when it is done." The moment arriving with the task not done is not the rule
        // going off — it is the rule becoming ready. Treating it as a missed occurrence rolled the
        // schedule past it and, for a one-shot, killed the rule outright: marking the task Done
        // afterwards then did nothing, ever.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val moment = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Archive the quarter",
                status = TaskStatus.Open,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = null,
                            firstOccurrence = moment,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(
                            persistentSetOf(TaskStatus.Done)
                        ),
                        resetToStatus = TaskStatus.InProgress,
                        termination = RecurrenceTermination.afterOccurrences(2),
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = moment
        f.engine().sweep()

        val waiting = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(TaskStatus.Open, waiting.status, "the status it waits for has not happened")
        assertEquals(
            2,
            waiting.recurrenceRules.single().first.termination.maxOccurrences,
            "and nothing has been spent, because nothing has happened",
        )

        // The status arrives a day later.
        f.clock.current = moment + 1.days
        f.repository.updateTask(
            assertNotNull(f.repository.getTaskById(task.id)).copy(status = TaskStatus.Done)
        )
        f.engine().sweep()

        assertEquals(
            TaskStatus.InProgress,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "now both halves are true, so the rule fires",
        )
    }

    @Test
    fun `a one-shot rule stops mattering once its moment has gone`() = runTest {
        // AfterTimeout with no period is a single occurrence. If it passes without firing — the
        // task already being in the status it resets to — nothing can wind it forward, so it sat
        // in the past being re-offered on every sweep, and reset the task months later the moment
        // the user gave it any other status.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val once = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "One-off review",
                status = TaskStatus.Open,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = null,
                            firstOccurrence = once,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = once
        f.engine().sweep()

        // Much later, the user picks the task up. Two sweeps: the first still remembers having
        // dealt with that occurrence, and forgets it on the way out — it is the one after that
        // finds the stale occurrence waiting and acts on it.
        f.clock.current = once + 60.days
        f.repository.updateTask(
            assertNotNull(f.repository.getTaskById(task.id)).copy(status = TaskStatus.Done)
        )
        f.engine().sweep()
        f.clock.current = once + 60.days + 1.minutes
        f.engine().sweep()

        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "a spent one-shot rule must not reopen a task two months later",
        )
    }

    @Test
    fun `stopping after a number of occurrences counts the ones that were not acted on`() = runTest {
        // The countdown lives in the repository and only moves when a fire changes the status. A
        // daily rule resetting to Open on a task left Open never changes anything, so a rule that
        // says it stops after two occurrences would otherwise run for ever.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Twice only",
                status = TaskStatus.Open,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = firstOccurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination.afterOccurrences(2),
                    ) to RecurrenceState()
                ),
            )
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()
        assertEquals(
            1,
            assertNotNull(f.repository.getTaskById(task.id)).recurrenceRules.single()
                .first.termination.maxOccurrences,
            "the first occurrence is spent even though the status had nowhere to go",
        )

        f.clock.current = firstOccurrence + 1.days
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(0, after.recurrenceRules.single().first.termination.maxOccurrences)
        assertTrue(
            after.recurrenceRules.single().first.isTerminated(f.clock.current),
            "and after the second the rule is finished",
        )
    }

    @Test
    fun `every sweep reports the next moment to the platform scheduler`() = runTest {
        // On Android the process is dead between sweeps, so the only thing that brings it back is
        // the wake-up the system holds. A sweep that does not report its next moment leaves that
        // wake-up wherever it was — which for a task created just now is however far out the last
        // sweep booked, and the reminder is simply never heard.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val reported = mutableListOf<Instant?>()
        f.onSwept = { reported += it }

        f.engine().sweep()
        assertEquals(listOf<Instant?>(null), reported, "nothing scheduled yet")

        f.repository.addTask(f.spaceId, title = "Call back", dueDate = start + 30.minutes)
        f.engine().sweep()

        assertEquals(
            start + 30.minutes,
            reported.last(),
            "a task created after the last sweep must move the next wake-up",
        )
    }

    @Test
    fun `a recurrence reset is not announced on top of the deadline`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val firstOccurrence = start + 1.hours
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Weekly review",
            status = TaskStatus.InProgress,
            dueDate = firstOccurrence,
            recurrenceRules = persistentListOf(
                RecurrenceRule(
                    timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                        period = RecurrencePeriod(days = 7),
                        firstOccurrence = firstOccurrence,
                        timezone = RecurrenceTimeZone.SystemDefault,
                    ),
                    statusChangeTrigger = null,
                    resetToStatus = TaskStatus.Open,
                ) to RecurrenceState()
            ),
        )

        f.engine().sweep()
        f.clock.current = firstOccurrence
        f.engine().sweep()
        // A second run: the reset was recorded a moment after the first run planned, so this is
        // where a duplicate would surface. The reset is worth announcing on its own — it is here
        // that it is not, because the deadline landed on the same moment and speaks for both.
        f.clock.current = firstOccurrence + 1.minutes
        f.engine().sweep()

        assertEquals(
            listOf("Due now"),
            f.notifier.alerts.map { it.body },
            "the deadline is the announcement; the reset the rule made in response is not a second one",
        )
    }

    @Test
    fun `a status rule does not fire again while the task sits in the status it was reset to`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Water cooler",
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination.afterOccurrences(5),
                    ) to RecurrenceState()
                ),
            )
        )

        f.repository.updateTask(assertNotNull(f.repository.getTaskById(task.id)).copy(status = TaskStatus.Done))
        f.engine().sweep()
        f.clock.current = start + 1.minutes
        f.engine().sweep()
        f.clock.current = start + 2.minutes
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(
            4,
            after.recurrenceRules.single().first.termination.maxOccurrences,
            "one completion is one occurrence, however many times the schedule is swept afterwards",
        )
    }

    @Test
    fun `a status the app changed by itself is announced`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val blocker = assertNotNull(f.repository.addTask(f.spaceId, title = "Sign the contract"))
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Start the work",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting on the contract"),
        )

        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.repository.updateTask(blocker.copy(status = TaskStatus.Done))
        f.engine().sweep()

        val alert = f.notifier.alerts.singleOrNull()
        assertNotNull(alert, "got ${f.notifier.alerts.map { it.title to it.body }}")
        assertEquals("Start the work", alert.title, "the task that changed, not the one that was finished")
        assertEquals("No longer blocked — now In Progress", alert.body)
    }

    @Test
    fun `a status the user set by hand is not announced`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(f.repository.addTask(f.spaceId, title = "Write it up"))

        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.repository.updateTask(task.copy(status = TaskStatus.InProgress))
        f.engine().sweep()

        assertTrue(
            f.notifier.alerts.isEmpty(),
            "telling someone what they just did themselves is noise: ${f.notifier.alerts.map { it.body }}",
        )
    }

    @Test
    fun `the next wake is the earliest event still ahead`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(f.spaceId, title = "Later", dueDate = start + 5.hours)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Sooner",
            dueDate = start + 4.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 2))),
        )

        val sweep = f.engine().sweep()

        assertEquals(
            start + 2.hours,
            sweep.nextAt,
            "the reminder two hours before the four-hour deadline is the first thing due",
        )
    }

    @Test
    fun `a day before a deadline keeps the time of day across a fall-back`() = runTest {
        // New York puts its clocks back at 02:00 on 2026-11-01, so the day that ends at 09:00 that
        // morning is 25 hours long.
        val due = at(2026, 11, 1, 9, 0, ny)
        val f = fixture(due - 10.days, zone = ny)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Quarterly report",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(days = 1))),
        )

        val reminder = assertNotNull(f.engine().sweep().nextAt)

        assertEquals(at(2026, 10, 31, 9, 0, ny), reminder, "the reminder keeps its wall-clock time")
        assertEquals(25.hours, due - reminder, "which is 25 real hours, the day the clocks go back")
    }

    @Test
    fun `a day before a deadline keeps the time of day across a spring-forward`() = runTest {
        // The mirror of the fall-back case: New York loses an hour at 02:00 on 2026-03-08, so the
        // day that ends at 09:00 that morning is 23 hours long.
        val due = at(2026, 3, 8, 9, 0, ny)
        val f = fixture(due - 10.days, zone = ny)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Tax return",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(days = 1))),
        )

        val reminder = assertNotNull(f.engine().sweep().nextAt)

        assertEquals(at(2026, 3, 7, 9, 0, ny), reminder, "the reminder keeps its wall-clock time")
        assertEquals(23.hours, due - reminder, "which is 23 real hours, the day the clocks go forward")
    }

    @Test
    fun `a reminder falling in an hour that does not exist is still given`() = runTest {
        // A day before 02:30 on the 9th is 02:30 on the 8th, and on the 8th New York has no 02:30
        // at all. Dropping the reminder would be the easy reading and the wrong one.
        val due = at(2026, 3, 9, 2, 30, ny)
        val f = fixture(due - 10.days, zone = ny)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Backup finishes",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(days = 1))),
        )

        val reminder = assertNotNull(f.engine().sweep().nextAt)

        assertEquals(
            emptyList(),
            LocalDateTime(2026, 3, 8, 2, 30).occurrencesIn(ny),
            "the hour the reminder was aimed at genuinely has no instant",
        )
        assertEquals(
            at(2026, 3, 8, 3, 30, ny),
            reminder,
            "so it moves on by the length of the jump rather than being lost",
        )
        assertTrue(reminder < due, "and it still arrives before the thing it warns about")
    }

    @Test
    fun `changing zone between runs does not repeat a reminder already given`() = runTest {
        // Flying west moves "a day before" an hour later. The reminder has already been given at
        // the New York moment; the Sydney-shaped one must not arrive on top of it.
        val due = at(2026, 11, 1, 9, 0, ny)
        val reminderInNewYork = at(2026, 10, 31, 9, 0, ny)
        val f = fixture(due - 10.days, zone = ny)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Board meeting",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(days = 1))),
        )

        f.engine().sweep()
        f.clock.current = reminderInNewYork
        f.engine().sweep()
        assertEquals(1, f.notifier.alerts.size, "the reminder is given once, in New York")

        f.zone = TimeZone.UTC
        f.clock.current = reminderInNewYork + 90.minutes
        f.engine().sweep()

        assertEquals(1, f.notifier.alerts.size, "and not again an hour later because the zone moved")
    }

    @Test
    fun `the same reminder is a different instant in a different zone`() = runTest {
        // The deadline is one fixed instant. A day before it is not: in New York that day contains
        // the end of daylight saving and is 25 hours long, in UTC it is 24.
        val due = at(2026, 11, 1, 9, 0, ny)
        val f = fixture(due - 10.days, zone = ny)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Board meeting",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(days = 1))),
        )

        val inNewYork = assertNotNull(f.engine().sweep().nextAt)
        f.zone = TimeZone.UTC
        val inUtc = assertNotNull(f.engine().sweep().nextAt)

        assertEquals(25.hours, due - inNewYork)
        assertEquals(24.hours, due - inUtc, "the zone is read again on every run, not captured at startup")
    }

    // ==================== Rules that wait for a place ====================

    private val office = GeoArea(name = "Office", point = GeoPoint(0.0, 0.0), radiusMeters = 200.0)

    /** Far enough from [office] to be outside it by any margin. */
    private val elsewhere = GeoFix(GeoPoint(1.0, 1.0))
    private val atTheOffice = GeoFix(GeoPoint(0.0, 0.0))

    private fun placeRule(
        direction: GeofenceDirection,
        area: GeoArea = office,
        resetTo: TaskStatus = TaskStatus.Open,
        timeTrigger: RecurrenceTrigger.TimeRecurrenceTrigger? = null,
    ) = RecurrenceRule(
        timeRecurrenceTrigger = timeTrigger,
        statusChangeTrigger = null,
        resetToStatus = resetTo,
        locationTrigger = RecurrenceTrigger.LocationChange(persistentSetOf(area), direction),
    ) to RecurrenceState()

    @Test
    fun `arriving somewhere brings a task round`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Entering)),
            )
        )

        // Somewhere else to begin with, so the office is known to have been outside.
        f.whereabouts = elsewhere
        f.engine().sweep()
        assertEquals(TaskStatus.Done, assertNotNull(f.repository.getTaskById(task.id)).status)

        f.clock.current = start + 1.hours
        f.whereabouts = atTheOffice
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "arriving at the office is what this rule was waiting for",
        )
    }

    @Test
    fun `being there already when the rule is written does not fire it`() = runTest {
        // The whole reason whereabouts distinguish "never looked" from "known to be outside": a
        // rule about home, written at home, must not go off the moment it is saved.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Entering)),
            )
        )

        f.whereabouts = atTheOffice
        f.engine().sweep()
        assertEquals(TaskStatus.Done, assertNotNull(f.repository.getTaskById(task.id)).status)

        f.clock.current = start + 1.hours
        f.engine().sweep()
        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "standing still is not arriving",
        )
    }

    @Test
    fun `a rule waiting to leave does not fire on arriving`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Leaving)),
            )
        )

        f.whereabouts = elsewhere
        f.engine().sweep()

        f.clock.current = start + 1.hours
        f.whereabouts = atTheOffice
        f.engine().sweep()
        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "this rule is about leaving",
        )

        f.clock.current = start + 2.hours
        f.whereabouts = elsewhere
        f.engine().sweep()
        assertEquals(TaskStatus.Open, assertNotNull(f.repository.getTaskById(task.id)).status)
    }

    @Test
    fun `one crossing fires the rule once however often it is swept`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        termination = RecurrenceTermination(
                            afterOccurrences = RecurrenceTerminationCondition.AfterOccurrences(2),
                        ),
                        locationTrigger = RecurrenceTrigger.LocationChange(
                            persistentSetOf(office),
                            GeofenceDirection.Entering,
                        ),
                    ) to RecurrenceState()
                ),
            )
        )

        f.whereabouts = elsewhere
        f.engine().sweep()

        f.clock.current = start + 1.hours
        f.whereabouts = atTheOffice
        f.engine().sweep()
        f.engine().sweep()
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(TaskStatus.Open, after.status)
        assertEquals(
            1,
            after.recurrenceRules.single().first.termination.maxOccurrences,
            "one arrival should spend one of the two allowed times",
        )
    }

    @Test
    fun `a crossing does not set off the rule that was waiting for a status`() = runTest {
        // Firing one rule cascades through the rest of the task's, so a departure that fires the
        // place rule leaves the task in a status the *other* rule is waiting for. Without a gate,
        // that second rule goes off on the same crossing and the departure lands the task
        // somewhere the user never asked it to be.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two rules",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    // First in the list, so the cascade reaches it before anything else.
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(
                            persistentSetOf(TaskStatus.InProgress)
                        ),
                        resetToStatus = TaskStatus.Done,
                    ) to RecurrenceState(),
                    placeRule(GeofenceDirection.Leaving, resetTo = TaskStatus.InProgress),
                ),
            )
        )

        f.whereabouts = atTheOffice
        f.engine().sweep()

        f.clock.current = start + 1.hours
        f.whereabouts = elsewhere
        f.engine().sweep()

        assertEquals(
            TaskStatus.InProgress,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "leaving is the place rule's business alone",
        )
    }

    @Test
    fun `a rule that only watches a place is not fired by a status changing`() = runTest {
        // It has no moment and no status of its own to wait for, so a crossing is the only thing
        // that can set it off. Asked as a standing question — "is the device outside?" — it is
        // true all day, and any other rule firing would drag it along.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two rules",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = RecurrenceTrigger.StatusChange(
                            persistentSetOf(TaskStatus.Done)
                        ),
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState(),
                    placeRule(GeofenceDirection.Leaving, resetTo = TaskStatus.InProgress),
                ),
            )
        )

        // Outside the office, and known to be — but never having crossed the boundary.
        f.whereabouts = elsewhere
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "the status rule fired; the place rule had no crossing to fire on",
        )
    }

    @Test
    fun `a plain schedule still comes round in a sweep where a boundary is crossed`() = runTest {
        // The crossing belongs to the rules watching for one, and to no others — but "to no
        // others" must not become "nothing else happens in this sweep". A task with an ordinary
        // daily rule, sitting on a device that has just walked into an office it knows nothing
        // about, is owed its occurrence like any other day.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val occurrence = start + 1.hours

        val watcher = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Entering)),
            )
        )
        val everyday = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Water the plants",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = occurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                    ) to RecurrenceState()
                ),
            )
        )

        f.whereabouts = elsewhere
        f.engine().sweep()

        // The moment and the arrival land in the same run.
        f.clock.current = occurrence
        f.whereabouts = atTheOffice
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(watcher.id)).status,
            "the arrival fired the rule watching for it",
        )
        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(everyday.id)).status,
            "and the daily task came round regardless of anyone walking anywhere",
        )
    }

    @Test
    fun `a rule whose end date has gone is not dragged along by another rule firing`() = runTest {
        // Firing one rule cascades through the rest of the task's, and the cascade judges each of
        // them on `shouldTrigger` alone. A rule that waits only for a place has no occurrence of
        // its own, so the termination check that compares the next occurrence against the end date
        // has nothing to compare — and without a check of its own such a rule outlives its end
        // date for ever.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val ended = RecurrenceTermination(
            onDate = RecurrenceTerminationCondition.OnDate(start - 1.days),
        )
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Two places",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    // Finished last week, and first in the list so the cascade reaches it first.
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Declined("this rule is over"),
                        termination = ended,
                        locationTrigger = RecurrenceTrigger.LocationChange(
                            persistentSetOf(office),
                            GeofenceDirection.Entering,
                        ),
                    ) to RecurrenceState(),
                    placeRule(GeofenceDirection.Entering, resetTo = TaskStatus.Open),
                ),
            )
        )

        f.whereabouts = elsewhere
        f.engine().sweep()

        f.clock.current = start + 1.hours
        f.whereabouts = atTheOffice
        f.engine().sweep()

        val after = assertNotNull(f.repository.getTaskById(task.id))
        assertEquals(TaskStatus.Open, after.status, "the live rule fired, as it should have")
        // On the finished rule's own bookkeeping, not on the task's status: the cascade fires
        // every rule that passes and leaves the task in the *last* one's reset status either way,
        // so the status alone cannot tell whether the finished rule went off on the way past.
        assertEquals(
            0,
            after.recurrenceRules.first().second.occurrenceCount,
            "the rule that ended last week came round again",
        )
    }

    @Test
    fun `a rule that can never come round again stops the device being watched`() = runTest {
        // A one-shot with a moment and a place fires once and is finished: it has no next
        // occurrence, which is how the calculator knows it will never fire again. Judged on the
        // rule alone rather than on what its schedule has left, its areas stay watched for ever —
        // and on a phone that is a permanent notification and a location reading every minute for
        // something that cannot happen.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val occurrence = start + 1.hours
        var watching: Boolean? = null

        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Hand in the form",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(
                placeRule(
                    direction = GeofenceDirection.Entering,
                    timeTrigger = RecurrenceTrigger.AfterTimeout(
                        // No period: one occurrence and no more.
                        period = null,
                        firstOccurrence = occurrence,
                        timezone = RecurrenceTimeZone.SystemDefault,
                    ),
                )
            ),
        )

        f.onWatchingPlaces = { watching = it.any }

        f.whereabouts = elsewhere
        f.engine().sweep()
        assertEquals(true, watching, "a rule is waiting on the office, so the device is watched")

        // The moment comes and the user arrives: the rule fires, and has nothing left.
        f.clock.current = occurrence
        f.whereabouts = atTheOffice
        f.engine().sweep()

        f.clock.current = occurrence + 1.hours
        f.engine().sweep()
        assertEquals(false, watching, "nothing is waiting on the office any more")
    }

    // ==================== Rules that wait on wifi and bluetooth ====================

    private val officeWifi = NearbySignal.Wifi("Office")

    private fun wifiRule(
        direction: SignalDirection,
        signal: NearbySignal = officeWifi,
        resetTo: TaskStatus = TaskStatus.Open,
        area: GeoArea? = null,
    ) = RecurrenceRule(
        timeRecurrenceTrigger = null,
        statusChangeTrigger = null,
        resetToStatus = resetTo,
        locationTrigger = area?.let {
            RecurrenceTrigger.LocationChange(persistentSetOf(it), GeofenceDirection.Entering)
        },
        wifiTrigger = RecurrenceTrigger.NearbyChange(persistentSetOf(signal), direction),
    ) to RecurrenceState()

    @Test
    fun `joining a network brings a task round`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Appearing)),
            )
        )

        // Off the network to begin with, so it is known to have been absent.
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()
        assertEquals(TaskStatus.Done, assertNotNull(f.repository.getTaskById(task.id)).status)

        f.clock.current = start + 1.hours
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "joining the office network is what this rule was waiting for",
        )
    }

    @Test
    fun `being on the network already when the rule is written does not fire it`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Appearing)),
            )
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()
        f.clock.current = start + 1.hours
        f.engine().sweep()

        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "staying on a network is not joining it",
        )
    }

    @Test
    fun `a network dropping for a moment does not fire a rule about leaving it`() = runTest {
        // The whole reason a signal has a grace period. A router that reboots is not the user
        // going out, and a rule that reset a task every time the wifi blinked would be unusable.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
            )
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        // Gone, but only just.
        f.clock.current = start + 30.seconds
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()
        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "a blink is not a departure",
        )

        // Still gone, well past the grace.
        f.clock.current = start + 10.minutes
        f.engine().sweep()
        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "gone for long enough is gone",
        )
    }

    @Test
    fun `sweeping often does not hold a departed network present for ever`() = runTest {
        // The grace is measured from the sweep that first noticed the signal missing, and that
        // moment is then left alone. Moved forward on every later sweep, it would renew itself —
        // and a phone that is moving sweeps every time its position updates, so a user walking
        // away from the house would keep the wifi "present" for the whole walk and the rule would
        // never fire at all.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
            )
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        // Gone, and then swept every half minute — well inside the two-minute grace each time.
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        repeat(10) { step ->
            f.clock.current = start + (30 * (step + 1)).seconds
            f.engine().sweep()
        }

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "five minutes off the network is a departure however often it was looked at",
        )
    }

    @Test
    fun `a blink after hours of quiet is still only a blink`() = runTest {
        // The scenario the grace is really for. A phone on a table runs no sweeps at all: nothing
        // moves, no radio changes, and the next booked one may be a day out. Then the router
        // reboots at midnight. If the grace were counted from when the network was last *seen*,
        // that moment would be hours old and the very first sweep after the blink would call it a
        // departure — waking the user to a task that came round because their router hiccupped.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
            )
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        // Eight hours later, and nothing has swept in between.
        f.clock.current = start + 8.hours
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "the network has been missing for one sweep, not for eight hours",
        )

        // Back a minute later, as a rebooting router is.
        f.clock.current = start + 8.hours + 1.minutes
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "and it never went away, so nothing has come back either",
        )
    }

    @Test
    fun `a sweep that could not look does not restart the clock on a departure`() = runTest {
        // The radios fail in bursts: a bluetooth stack wedges, location services go off, the
        // source throws. Every one of those leaves a kind unmeasured for a sweep or several — and
        // if that wiped the note of when a signal went missing, the grace would begin again
        // afterwards. Under a source that flaps, the departure would then be postponed for ever.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
            )
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        // Gone, and noticed.
        f.clock.current = start + 1.minutes
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        // Now the source cannot say anything at all for a while.
        f.nearby = NearbySignals.Unknown
        f.clock.current = start + 2.minutes
        f.engine().sweep()
        f.clock.current = start + 3.minutes
        f.engine().sweep()

        // It comes back, still with nothing on the network. The grace expired during the silence.
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.clock.current = start + 4.minutes
        f.engine().sweep()

        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "it went at one minute past and the grace is two; the blind sweeps in between change nothing",
        )
    }

    @Test
    fun `a sweep is booked for the moment a held signal stops being held`() = runTest {
        // Nothing else will happen to make the departure noticed: the radio has already changed,
        // the user is sitting still, and with no timed tasks the next ordinary sweep is a day out.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        var nextAt: Instant? = null
        f.onSwept = { nextAt = it }

        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Lock up",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        f.clock.current = start + 1.minutes
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        assertEquals(
            start + 1.minutes + Geofencing.WIFI_GRACE,
            nextAt,
            "the grace running out is itself something to come back for",
        )
    }

    @Test
    fun `a sweep that could not look still books the one that can`() = runTest {
        // The appointment matters as much as the clock. A phone that has gone still after a
        // disconnection will do nothing else on its own, so the sweep that notices the departure
        // has to be booked. Worked out from this run's reading alone, a wedged radio would swallow
        // the appointment along with the answer and the departure would wait for a day.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        var nextAt: Instant? = null
        f.onSwept = { nextAt = it }

        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Lock up",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()

        // Noticed missing.
        f.clock.current = start + 1.minutes
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        // And now the radio will not answer at all.
        f.clock.current = start + 90.seconds
        f.nearby = NearbySignals.Unknown
        f.engine().sweep()

        assertEquals(
            start + 1.minutes + Geofencing.WIFI_GRACE,
            nextAt,
            "the grace still runs out at the same moment, and something still has to come back for it",
        )
    }

    @Test
    fun `a rule wanting a place and a network is not fired by the place alone`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    wifiRule(SignalDirection.Appearing, area = office)
                ),
            )
        )

        f.whereabouts = elsewhere
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        // At the office, but on somebody else's network: half of what was asked for.
        f.clock.current = start + 1.hours
        f.whereabouts = atTheOffice
        f.engine().sweep()
        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "the wifi condition was not met",
        )

        // And now on the network too.
        f.clock.current = start + 2.hours
        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = setOf(officeWifi.key))
        f.engine().sweep()
        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "both conditions hold, and joining the network is the crossing",
        )
    }

    @Test
    fun `a device that cannot see wifi at all fires nothing`() = runTest {
        // What iOS and the browser answer. It must not read as a device provably off every
        // network, or every rule about leaving one goes off the moment it is written.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(wifiRule(SignalDirection.Disappearing)),
            )
        )

        f.nearby = NearbySignals.Unknown
        f.engine().sweep()
        f.clock.current = start + 1.hours
        f.engine().sweep()

        assertEquals(TaskStatus.Done, assertNotNull(f.repository.getTaskById(task.id)).status)
    }

    @Test
    fun `a rule is watched whichever kind of condition it carries`() = runTest {
        // Wifi and bluetooth are separate conditions on a rule, and the engine gathers what to
        // watch from the rule's conditions rather than from the fields by name. Gathered by name,
        // splitting the two left every rule using the new ones watching nothing at all: the radios
        // were never read and nothing ever fired.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        var needs: ScheduledEventEngine.WatchNeeds? = null
        f.onWatchingPlaces = { needs = it }

        val car = NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car")
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Two conditions",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(
                RecurrenceRule(
                    timeRecurrenceTrigger = null,
                    statusChangeTrigger = null,
                    resetToStatus = TaskStatus.Open,
                    wifiTrigger = RecurrenceTrigger.NearbyChange(
                        persistentSetOf(officeWifi),
                        SignalDirection.Appearing,
                    ),
                    bluetoothTrigger = RecurrenceTrigger.NearbyChange(
                        persistentSetOf(car),
                        SignalDirection.Disappearing,
                    ),
                ) to RecurrenceState()
            ),
        )

        f.nearby = NearbySignals(
            kinds = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
            present = emptySet(),
        )
        f.engine().sweep()

        assertEquals(
            ScheduledEventEngine.WatchNeeds(
                places = false,
                signals = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
            ),
            needs,
            "both conditions are watched, and each is named for what it needs",
        )
        assertTrue(f.timesAskedNearby > 0, "and the radios were actually read")
    }

    @Test
    fun `nothing is asked of the radios when no rule watches one`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(spaceId = f.spaceId, title = "Pay rent", dueDate = start + 2.hours)

        f.engine().sweep()
        f.engine().sweep()

        assertEquals(0, f.timesAskedNearby, "the radios were read for nothing")
    }

    @Test
    fun `a rule waiting on a network keeps the watch running`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        var needs: ScheduledEventEngine.WatchNeeds? = null
        f.onWatchingPlaces = { needs = it }

        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Hand in the form",
            status = TaskStatus.Done,
            recurrenceRules = persistentListOf(wifiRule(SignalDirection.Appearing)),
        )

        f.nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet())
        f.engine().sweep()

        assertEquals(
            ScheduledEventEngine.WatchNeeds(places = false, signals = setOf(SignalKind.Wifi)),
            needs,
            "a rule watching a network needs the device kept awake as much as one watching a place" +
                " — and the two are reported apart, because a platform can honour one and not the other",
        )
    }

    @Test
    fun `nothing is asked of the platform when no rule watches a place`() = runTest {
        // Positioning costs battery and, on a phone, a permission prompt. A database with no such
        // rule in it must provoke neither.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        f.repository.addTask(
            spaceId = f.spaceId,
            title = "Pay rent",
            dueDate = start + 2.hours,
        )

        f.engine().sweep()
        f.engine().sweep()

        assertEquals(0, f.timesAsked, "the device was asked where it is for nothing")
    }

    @Test
    fun `a device that cannot say where it is fires nothing`() = runTest {
        // What a desktop answers, and what a phone answers when the permission was refused. It
        // must not read as a device provably outside every area, or every leaving rule goes off.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Lock up",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Leaving)),
            )
        )

        f.whereabouts = null
        f.engine().sweep()
        f.engine().sweep()

        assertEquals(TaskStatus.Done, assertNotNull(f.repository.getTaskById(task.id)).status)
        assertEquals(2, f.timesAsked, "asked once a sweep, no more")
    }

    @Test
    fun `a moment that comes round while the device is elsewhere waits for it to arrive`() = runTest {
        // A rule with both a moment and a place reads as the moment arming it and the place
        // setting it off — the same pairing a status makes. Spending the occurrence while the user
        // is nowhere near would leave the task never coming round at all.
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val occurrence = start + 1.hours
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Water the office plants",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(
                    placeRule(
                        direction = GeofenceDirection.Entering,
                        timeTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod(days = 1),
                            firstOccurrence = occurrence,
                            timezone = RecurrenceTimeZone.SystemDefault,
                        ),
                    )
                ),
            )
        )

        f.whereabouts = elsewhere
        f.engine().sweep()

        f.clock.current = occurrence
        f.engine().sweep()
        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "the moment has come but the user has not",
        )

        f.clock.current = occurrence + 30.minutes
        f.whereabouts = atTheOffice
        f.engine().sweep()
        assertEquals(
            TaskStatus.Open,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "armed by the moment, fired by arriving",
        )
    }

    @Test
    fun `whereabouts survive a restart so that no crossing is invented`() = runTest {
        val start = at(2026, 6, 1, 9, 0)
        val f = fixture(start)
        val task = assertNotNull(
            f.repository.addTask(
                spaceId = f.spaceId,
                title = "Hand in the form",
                status = TaskStatus.Done,
                recurrenceRules = persistentListOf(placeRule(GeofenceDirection.Entering)),
            )
        )

        f.whereabouts = atTheOffice
        f.engine().sweep()

        // A fresh engine over the same store: the process died and came back, still at the office.
        f.clock.current = start + 1.hours
        f.engine().sweep()

        assertEquals(
            TaskStatus.Done,
            assertNotNull(f.repository.getTaskById(task.id)).status,
            "a restart must not read as having arrived all over again",
        )
    }
}
