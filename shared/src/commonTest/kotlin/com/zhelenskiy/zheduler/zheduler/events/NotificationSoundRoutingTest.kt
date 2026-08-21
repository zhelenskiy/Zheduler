@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
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

    private fun engine(
        repository: TaskRepository,
        notifier: EventNotifier,
        clock: Clock,
        default: NotificationSound,
        // Shared between the runs of one test: a fresh store is a first run, and a first run says
        // nothing about the past.
        store: ScheduleStore,
    ) = ScheduledEventEngine(
        repository = repository,
        notifier = notifier,
        store = store,
        clock = clock,
        timeZone = { TimeZone.UTC },
        defaultSound = { default },
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
        engine(repository, notifier, clock, NotificationSound.Silent, store).sweep()
        clock.current = start + 1.hours
        engine(repository, notifier, clock, NotificationSound.Silent, store).sweep()

        assertEquals(
            NotificationSound.Bell,
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
        engine(repository, notifier, clock, NotificationSound.Chime, store).sweep()
        clock.current = start + 1.hours
        engine(repository, notifier, clock, NotificationSound.Chime, store).sweep()

        assertEquals(
            NotificationSound.Chime,
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
        engine(repository, notifier, clock, NotificationSound.Chime, store).sweep()
        clock.current = due
        engine(repository, notifier, clock, NotificationSound.Chime, store).sweep()

        assertEquals(
            NotificationSound.Chime,
            notifier.alerts.single().sound,
            "nobody chose a sound for a deadline arriving, so the app's own choice stands",
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
