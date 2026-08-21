package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sound the user added has to outlive the file it came from, and has to stay the sound it was
 * chosen as. Both are things nothing else in the app would notice going wrong: the reminder still
 * fires, and simply makes the wrong noise, or none.
 *
 * These write real files. Where they write is set for the whole test run in `build.gradle.kts` —
 * not here, because the directory is worked out once at first use, and by the time one test class
 * is being built another may already have asked.
 */
class SoundLibraryTest {

    @BeforeTest
    fun writeSomewhereOfOurOwn() {
        // Set by the build, and checked here because the cost of it not being set is invisible:
        // these tests would pass while adding and deleting files in the library the developer's
        // own copy of the app is reading.
        assertNotNull(
            System.getProperty(DATA_DIRECTORY_PROPERTY),
            "$DATA_DIRECTORY_PROPERTY is unset, so this would write to the real sound library",
        )
    }

    private val added = mutableListOf<String>()

    private suspend fun store(id: String, bytes: ByteArray): Boolean =
        SoundLibrary.store(id, bytes).also { added += id }

    @AfterTest
    fun tidy() = runTest {
        added.forEach { SoundLibrary.remove(it) }
    }

    @Test
    fun aStoredSoundIsTheSoundThatWasStored() = runTest {
        val bytes = ByteArray(2048) { (it % 251).toByte() }

        assertTrue(store("zheduler-test-tone.wav", bytes), "the copy was refused")

        assertContentEquals(bytes, SoundLibrary.read("zheduler-test-tone.wav"))
    }

    @Test
    fun aSoundThatWasNeverAddedReadsAsNothing() = runTest {
        assertNull(SoundLibrary.read("zheduler-test-absent.wav"))
    }

    @Test
    fun aRemovedSoundIsGone() = runTest {
        store("zheduler-test-removed.wav", ByteArray(64) { 1 })

        SoundLibrary.remove("zheduler-test-removed.wav")

        assertNull(SoundLibrary.read("zheduler-test-removed.wav"))
    }

    @Test
    fun aSoundWhoseCopyIsGoneFallsBackRatherThanGoingQuiet() = runTest {
        // What the user is left with after deleting a sound another reminder was still using.
        // Nothing else notices: the reminder fires on time and makes no noise whatsoever.
        val missing = ChosenSound.of(CustomSound("zheduler-test-never-stored.wav", "Gone.wav"))

        assertEquals(
            ChosenSound(NotificationSound.System),
            missing.playable(),
            "with no copy left, the choice stands on what it was made alongside",
        )
    }

    @Test
    fun aSoundThatIsStillThereIsStillTheChoice() = runTest {
        val id = "zheduler-test-present.wav"
        store(id, ByteArray(64) { 7 })
        val chosen = ChosenSound.of(CustomSound(id, "Present.wav"))

        assertEquals(chosen, chosen.playable(), "the copy is there, so nothing falls back")
    }

    @Test
    fun twoFilesOfTheSameNameAreTwoSounds() {
        // The library is one flat directory. Were the file's own name kept, a second alarm.wav
        // would land on the first, and a reminder set months ago would start playing it.
        val first = newSoundId("alarm.wav")
        val second = newSoundId("alarm.wav")

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".wav"), "iOS decides what it can play by the extension: $first")
        assertTrue(second.endsWith(".wav"), "iOS decides what it can play by the extension: $second")
    }

    @Test
    fun aFileWithNoExtensionIsStoredWithoutInventingOne() {
        assertEquals("abc", newSoundId("some tone", unique = "abc"))
    }

    @Test
    fun theExtensionIsSettledWhenTheSoundIsStored() {
        // iOS reads the name and nothing else, and knows one spelling of the two. Settling it here
        // rather than at the far end is what keeps a file that previews from arriving silent.
        assertEquals("abc.aiff", newSoundId("Tone.AIF", unique = "abc"))
        assertEquals("abc.aiff", newSoundId("Tone.aiff", unique = "abc"))
        assertEquals("abc.wav", newSoundId("TONE.WAV", unique = "abc"))
    }
}
