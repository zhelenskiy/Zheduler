package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the app sounds like before anybody says otherwise, and what a stored copy turns into. */
class NotificationSettingsTest {

    private val stored = Json { ignoreUnknownKeys = true }

    @Test
    fun `an app nobody has configured sounds like one of its own tones`() {
        // Not the platform's: a sound that is the same on every device is a better first
        // impression than whatever this one happens to announce mail with, and "Default" naming a
        // sound nobody chose was the part that read as a riddle.
        val fresh = NotificationSettings()

        for (role in SoundRole.entries) {
            val sound = fresh.forRole(role)
            assertNull(sound.custom, "${role.name} cannot start with a file nobody has added")
            assertTrue(
                sound.builtin.isBundled,
                "${role.name} starts as ${sound.builtin}, which is not a tone the app ships",
            )
        }
    }

    @Test
    fun `a sound chosen before the three were told apart is kept for all three`() {
        // Every copy on disk is this: one sound, for everything the app had to say. Someone who
        // set the app to silence meant it, and an update is no reason to start making noise.
        val before = """{"defaultSound":"Silent"}"""

        val settings = stored.decodeFromString<NotificationSettings>(before).normalized()

        for (role in SoundRole.entries) {
            assertEquals(ChosenSound.of(NotificationSound.Silent), settings.forRole(role), role.name)
        }
    }

    @Test
    fun `the old default is carried across as the platform sound it named`() {
        // There is only a file to read if someone opened the menu and picked something, and what
        // "Default" meant there was the platform's own sound. It is called System now; the choice
        // is the same one, and renaming it is no reason to overrule it.
        val before = """{"defaultSound":"Default"}"""

        val settings = stored.decodeFromString<NotificationSettings>(before).normalized()

        for (role in SoundRole.entries) {
            assertEquals(ChosenSound.of(NotificationSound.System), settings.forRole(role), role.name)
        }
    }

    @Test
    fun `an app with nothing stored at all is the one that starts on a tone of its own`() {
        assertEquals(NotificationSettings(), NotificationSettings().normalized())
    }

    @Test
    fun `what an older copy said is not written back out again`() {
        val carried = NotificationSettings(defaultSound = NotificationSound.Bell).normalized()

        assertNull(carried.defaultSound, "it has been folded in, and has no second meaning left")
    }

    @Test
    fun `the app itself is never offered the choice of deferring to itself`() {
        assertFalse(
            NotificationSound.Default in NotificationSound.appWide,
            "the app's own sounds are what everything else defers to, so this would be a circle",
        )
        assertTrue(NotificationSound.System in NotificationSound.appWide)
    }

    @Test
    fun `a choice of one kind leaves the other two alone`() {
        val settings = NotificationSettings().with(SoundRole.DueTime, ChosenSound.of(NotificationSound.Alarm))

        assertEquals(ChosenSound.of(NotificationSound.Alarm), settings.forRole(SoundRole.DueTime))
        assertEquals(NotificationSettings().reminders, settings.reminders)
        assertEquals(NotificationSettings().announcements, settings.announcements)
    }
}
