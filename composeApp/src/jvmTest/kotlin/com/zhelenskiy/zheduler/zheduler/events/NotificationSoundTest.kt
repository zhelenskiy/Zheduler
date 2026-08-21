@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A sound the user picked has to survive as far as the thing that makes the noise. The two halves
 * checked here are the ones that fail quietly: a tone that ships with the app but cannot be found
 * at runtime, and a notifier that accepts a sound and drops it.
 */
class NotificationSoundTest {

    private fun alert(sound: NotificationSound) = TaskAlert(
        id = "test:sound:${sound.name}",
        taskId = "TEST-1",
        spaceId = "space",
        title = "Zheduler test",
        body = "Due in 1 hour",
        at = Clock.System.now(),
        sound = sound,
    )

    @Test
    fun everyBundledToneIsActuallyThere() = runTest {
        for (sound in NotificationSound.entries.filter { it.isBundled }) {
            val bytes = readBundledTone(sound)
            assertNotNull(bytes, "${sound.name} says it ships with the app but nothing was found")
            assertTrue(bytes.size > 1000, "${sound.name} is too small to be a tone: ${bytes.size} bytes")
            // A WAV, so every platform can play it without a codec.
            assertTrue(
                bytes.decodeToString(0, 4) == "RIFF",
                "${sound.name} is not a WAV",
            )
        }
    }

    @Test
    fun aPlatformSoundHasNoFileOfItsOwn() = runTest {
        for (sound in NotificationSound.entries.filterNot { it.isBundled }) {
            assertNull(readBundledTone(sound), "${sound.name} belongs to the platform")
        }
    }

    @Test
    fun androidsOwnCopyOfEachToneIsTheSameFile() = runTest {
        // Android's channels play out of `androidApp/src/main/res/raw`, a second copy that nothing
        // in the build keeps in step with the shared one: a file renamed, dropped, or edited on one
        // side alone leaves chime and bell silent or wrong on Android with every other test green.
        val raw = File(repositoryRoot(), "androidApp/src/main/res/raw")
        for (sound in NotificationSound.entries.filter { it.isBundled }) {
            val copy = File(raw, "${sound.bundledName}.wav")
            assertTrue(copy.isFile, "${copy.path} is missing: Android has no ${sound.name} to play")
            assertContentEquals(
                readBundledTone(sound),
                copy.readBytes(),
                "${sound.name} is a different tone on Android than everywhere else",
            )
        }
    }

    /** The directory the build was started from, found by the file that only the root has. */
    private fun repositoryRoot(): File {
        val start = System.getProperty("user.dir")
        var directory: File? = File(start)
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        return assertNotNull(directory, "no settings.gradle.kts anywhere above $start")
    }

    @Test
    fun macOsIsAskedForTheSoundTheAlertChose() {
        // What the notifier does with a sound, rather than merely that it survived being handed
        // one: posting swallows every failure, so a test that only posts cannot fail.
        fun scriptFor(sound: NotificationSound) = MacNotificationCentre.scriptFor(alert(sound))

        assertTrue(
            scriptFor(NotificationSound.Default).endsWith("sound name \"Ping\""),
            "the default has to name a sound: Notification Centre is silent unless one is named",
        )
        assertTrue(
            scriptFor(NotificationSound.Alarm).endsWith("sound name \"Sosumi\""),
            "an alarm is a different sound, not the same one",
        )
        for (silent in listOf(NotificationSound.Silent, NotificationSound.Chime, NotificationSound.Bell)) {
            assertFalse(
                scriptFor(silent).contains("sound name"),
                "${silent.name} is not Notification Centre's to play",
            )
        }
    }

    @Test
    fun theUsersTextCannotEscapeTheScript() {
        val script = MacNotificationCentre.scriptFor(
            alert(NotificationSound.Default).copy(title = """a " quote""", body = """a \ slash""")
        )

        // The exact text, because escaping the quote before the backslash also puts both characters
        // in the script — and leaves the quote open.
        assertTrue(
            script.contains("""with title "a \" quote""""),
            "a quote in a title is escaped, not left to end the string: $script",
        )
        assertTrue(
            script.contains("""notification "a \\ slash""""),
            "and a backslash is doubled: $script",
        )
    }
}
