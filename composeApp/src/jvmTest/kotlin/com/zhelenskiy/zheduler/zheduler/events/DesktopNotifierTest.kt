@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The desktop had a notifier that could not notify: the tray balloon it used does nothing at all
 * on macOS, so the icon appeared and every reminder was swallowed. What is checked here is that
 * this desktop gets a notifier that reaches something real, and that posting through it completes
 * rather than throwing or hanging.
 */
class DesktopNotifierTest {

    @Test
    fun thisDesktopGetsANotifierThatCanActuallyReachTheUser() {
        val notifier = createEventNotifier()

        assertFalse(
            notifier === NoOpEventNotifier,
            "a desktop with a notification service should not be given a notifier that drops everything",
        )
        assertTrue(
            if (isMacOs) notifier === MacNotificationCentre else notifier === TrayBalloon,
            "macOS is reached through its own notification service; the balloon is for desktops it works on",
        )
    }

    @Test
    fun postingReachesTheDesktopWithoutFailing() = runTest {
        // Actually posts one. On this machine that is a real notification; the point of the test is
        // that the call completes — the previous implementation returned a no-op notifier whenever
        // the tray could not be built, and nobody found out.
        createEventNotifier().post(
            TaskAlert(
                id = "test:desktop-notifier",
                taskId = "TEST-1",
                spaceId = "space",
                title = "Zheduler test",
                body = "Due in 1 hour",
                at = Clock.System.now(),
            )
        )
    }

    @Test
    fun aTitleWithAQuoteDoesNotBreakTheScript() = runTest {
        // The title is the user's text and goes into an AppleScript literal.
        createEventNotifier().post(
            TaskAlert(
                id = "test:desktop-quoting",
                taskId = "TEST-2",
                spaceId = "space",
                title = """He said "now" \ tomorrow""",
                body = """Body with "quotes" and a \ backslash""",
                at = Clock.System.now(),
            )
        )
    }
}
