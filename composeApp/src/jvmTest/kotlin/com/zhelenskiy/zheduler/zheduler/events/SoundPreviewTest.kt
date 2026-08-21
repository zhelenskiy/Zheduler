package com.zhelenskiy.zheduler.zheduler.events

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A preview is a promise: the sound played while choosing is the sound the notification will make.
 * The two are built from one name, so what is checked here is that the name reaches a sound this
 * machine actually has, and that the app's own tones are not looked for among the system's.
 *
 * Only macOS keeps its notification sounds as files a program may play, so that is the only
 * desktop where the promise can be kept or tested; elsewhere there is nothing to name and the
 * checks below have nothing to say.
 */
class SoundPreviewTest {

    @Test
    fun theSoundPreviewedIsTheSoundNotified() {
        if (!isMacOs) return
        for (sound in NotificationSound.entries) {
            val notified = macSoundName(sound) ?: continue
            val previewed = assertNotNull(
                previewCommand(sound),
                "${sound.name} is notified as $notified but nothing plays it while choosing",
            )
            assertTrue(
                previewed.any { it.endsWith("/$notified.aiff") },
                "${sound.name} previews as $previewed and notifies as $notified",
            )
        }
    }

    @Test
    fun theSoundsThePlatformOwnsAreAllAskedFor() {
        if (!isMacOs) return
        // Otherwise every check below is satisfied by a mapping that names nothing at all.
        for (sound in listOf(NotificationSound.Default, NotificationSound.Alarm)) {
            assertNotNull(previewCommand(sound), "${sound.name} is the platform's, and goes unplayed")
        }
    }

    @Test
    fun everySystemToneThePreviewNamesIsOnThisMachine() {
        if (!isMacOs) return
        for (sound in NotificationSound.entries) {
            val file = previewCommand(sound)?.last() ?: continue
            assertTrue(File(file).isFile, "$file is not there, so ${sound.name} previews as silence")
        }
    }

    @Test
    fun whatTheAppBroughtWithItIsPlayedByTheAppItself() {
        // Guarded like its siblings: off macOS nothing has a command, and the test would pass
        // without ever reaching the distinction it is about.
        if (!isMacOs) return
        for (sound in NotificationSound.entries.filter { it.isBundled } + NotificationSound.Silent) {
            assertNull(previewCommand(sound), "${sound.name} is not the system's to play")
        }
    }
}
