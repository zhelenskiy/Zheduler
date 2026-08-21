@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTimeZone
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.TaskNotification
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.db.toJson
import com.zhelenskiy.zheduler.zheduler.db.toNotificationList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A sound is chosen in one place and heard in another, with a database and a schedule in between.
 * What is checked here is that the choice survives the journey: from the reminder it was set on,
 * through storage, to the alert handed to whatever makes the noise.
 */
class NotificationSoundRoutingTest {

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

    private val start = LocalDateTime(2026, 6, 1, 9, 0).toInstant(TimeZone.UTC)

    private suspend fun fixture(): Triple<TaskRepository, String, MutableClock> {
        val clock = MutableClock(start)
        val repository = InMemoryTaskRepository(clock)
        val space = assertNotNull(repository.createSpace("Test", "TEST"))
        return Triple(repository, space.id, clock)
    }

    /** The app set to one sound for everything it has to say, as it was before the three. */
    private fun allOf(sound: NotificationSound) = NotificationSettings(
        reminders = ChosenSound.of(sound),
        dueTime = ChosenSound.of(sound),
        announcements = ChosenSound.of(sound),
    )

    private fun engine(
        repository: TaskRepository,
        notifier: EventNotifier,
        clock: Clock,
        default: NotificationSettings,
        // Shared between the runs of one test: a fresh store is a first run, and a first run says
        // nothing about the past.
        store: ScheduleStore,
    ) = ScheduledEventEngine(
        repository = repository,
        notifier = notifier,
        store = store,
        clock = clock,
        timeZone = { TimeZone.UTC },
        appSounds = { default },
    )

    @Test
    fun `a reminder is delivered with the sound it was given`() = runTest {
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        repository.addTask(
            spaceId = spaceId,
            title = "Pay rent",
            dueDate = start + 2.hours,
            notifications = persistentListOf(
                TaskNotification(RecurrencePeriod(hours = 1), NotificationSound.Bell)
            ),
        )

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, allOf(NotificationSound.Silent), store).sweep()
        clock.current = start + 1.hours
        engine(repository, notifier, clock, allOf(NotificationSound.Silent), store).sweep()

        assertEquals(
            ChosenSound.of(NotificationSound.Bell),
            notifier.alerts.single().sound,
            "the reminder asked for a bell, not for whatever the app usually sounds like",
        )
    }

    @Test
    fun `a reminder nobody chose a sound for follows the app`() = runTest {
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        repository.addTask(
            spaceId = spaceId,
            title = "Pay rent",
            dueDate = start + 2.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1))),
        )

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()
        clock.current = start + 1.hours
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()

        assertEquals(
            ChosenSound.of(NotificationSound.Chime),
            notifier.alerts.single().sound,
            "the picker was never opened for this reminder, so the app's own sound stands",
        )
    }

    @Test
    fun `a deadline is delivered with the sound the app is set to`() = runTest {
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        val due = start + 1.hours
        repository.addTask(spaceId = spaceId, title = "File the return", dueDate = due)

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()
        clock.current = due
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()

        assertEquals(
            ChosenSound.of(NotificationSound.Chime),
            notifier.alerts.single().sound,
            "nobody chose a sound for a deadline arriving, so the app's own choice stands",
        )
    }

    @Test
    fun `each kind of announcement takes the sound set for its own kind`() = runTest {
        // The three are set apart so they can differ; an engine that read one of them for
        // everything would pass every test above and none of this one.
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        val due = start + 1.hours
        repository.addTask(
            spaceId = spaceId,
            title = "File the return",
            dueDate = due,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(minutes = 30))),
        )
        val sounds = NotificationSettings(
            reminders = ChosenSound.of(NotificationSound.Bell),
            dueTime = ChosenSound.of(NotificationSound.Alarm),
            announcements = ChosenSound.of(NotificationSound.Silent),
        )

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, sounds, store).sweep()
        clock.current = due - 30.minutes
        engine(repository, notifier, clock, sounds, store).sweep()
        clock.current = due
        engine(repository, notifier, clock, sounds, store).sweep()

        assertEquals(
            listOf(ChosenSound.of(NotificationSound.Bell), ChosenSound.of(NotificationSound.Alarm)),
            notifier.alerts.map { it.sound },
            "the warning is a reminder and the deadline is a due time, and they are set differently",
        )
    }

    @Test
    fun `a rule coming round sounds like the app talking about itself`() = runTest {
        // The third of the three, and the one neither of the tests above reaches: not a deadline
        // and not a warning, but the app noting a recurring task has come round again.
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        val firstOccurrence = start + 1.hours
        repository.addTask(
            spaceId = spaceId,
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
        val sounds = NotificationSettings(
            reminders = ChosenSound.of(NotificationSound.Silent),
            dueTime = ChosenSound.of(NotificationSound.Silent),
            announcements = ChosenSound.of(NotificationSound.Bell),
        )

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, sounds, store).sweep()
        clock.current = firstOccurrence
        engine(repository, notifier, clock, sounds, store).sweep()

        assertEquals(
            ChosenSound.of(NotificationSound.Bell),
            notifier.alerts.single().sound,
            "the app announcing itself uses what it was set to for that, not the other two",
        )
    }

    @Test
    fun `a task chooses what its own deadline sounds like`() = runTest {
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        val due = start + 1.hours
        val task = assertNotNull(
            repository.addTask(
                spaceId = spaceId,
                title = "Board meeting",
                dueDate = due,
                dueSound = ChosenSound.of(NotificationSound.Bell),
            )
        )
        assertEquals(ChosenSound.of(NotificationSound.Bell), task.dueSound, "stored as chosen")

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()
        clock.current = due
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()

        assertEquals(
            ChosenSound.of(NotificationSound.Bell),
            notifier.alerts.single().sound,
            "this task asked for a bell at its deadline, whatever the app is set to",
        )
    }

    @Test
    fun `a sound written into the stored form comes back out of it`() {
        // The column a reminder ends up in holds JSON, so what has to survive is the serialized
        // form. A repository that keeps objects in memory would prove only that a field was copied.
        val stored = listOf(
            TaskNotification(RecurrencePeriod(hours = 1), NotificationSound.Alarm),
            TaskNotification(RecurrencePeriod(minutes = 10), NotificationSound.Silent),
        ).toJson()

        assertEquals(
            listOf(NotificationSound.Alarm, NotificationSound.Silent),
            stored.toNotificationList().map { it.sound },
            "each reminder keeps its own sound, in its own order: $stored",
        )
        assertTrue(stored.contains("Alarm"), "the sound is written by name: $stored")
    }

    @Test
    fun `a reminder set to a sound of the user's own is delivered with it`() = runTest {
        // The same journey as a builtin, through the field beside it — a reminder's custom sound
        // is stored apart from its name, and a mapping that dropped one would leave the other
        // looking perfectly correct.
        val (repository, spaceId, clock) = fixture()
        val notifier = RecordingNotifier()
        val mine = ChosenSound.of(CustomSound("tone-1.wav", "My tone.wav"))
        repository.addTask(
            spaceId = spaceId,
            title = "Pay rent",
            dueDate = start + 2.hours,
            notifications = persistentListOf(TaskNotification(RecurrencePeriod(hours = 1), mine)),
        )

        val store = InMemoryScheduleStore()
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()
        clock.current = start + 1.hours
        engine(repository, notifier, clock, allOf(NotificationSound.Chime), store).sweep()

        assertEquals(mine, notifier.alerts.single().sound, "the file the user picked, not a tone")
    }

    @Test
    fun `a reminder's own sound survives being stored and read back`() {
        val stored = listOf(
            TaskNotification(RecurrencePeriod(hours = 1), ChosenSound.of(CustomSound("t.wav", "T.wav"))),
        ).toJson()

        val reread = stored.toNotificationList().single()

        assertEquals(CustomSound("t.wav", "T.wav"), reread.customSound)
        assertEquals(
            ChosenSound.of(CustomSound("t.wav", "T.wav")),
            reread.chosen,
            "the two fields are one choice again on the way out: $stored",
        )
    }

    @Test
    fun `a notification stored before there were sounds still reads`() {
        // What sits in the notificationsJson column of every task written before this feature: a
        // time and nothing else. It has to decode, and to mean what it meant when it was written.
        val storedBefore = """[{"timeBeforeDeadline":{"hours":1}}]"""

        val notifications = storedBefore.toNotificationList()

        assertEquals(RecurrencePeriod(hours = 1), notifications.single().timeBeforeDeadline)
        assertEquals(
            NotificationSound.Default,
            notifications.single().sound,
            "a reminder set before the choice existed asks for whatever the app does",
        )
    }
}
