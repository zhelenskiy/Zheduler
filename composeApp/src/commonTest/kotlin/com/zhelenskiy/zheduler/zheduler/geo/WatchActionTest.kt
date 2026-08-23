package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether the watch starts, stops, or is left alone.
 *
 * Both ways of getting it wrong are quiet. A watch that fails to start is a rule that never fires
 * and says nothing about why; a watch that fails to stop is a permanent notification and a radio
 * running for nothing until the process dies.
 */
class WatchActionTest {

    @Test
    fun `nothing to watch and nothing running is nothing to do`() {
        assertEquals(
            WatchAction.LeaveAlone,
            watchAction(wanted = false, running = false, signature = 0, startedWith = 0),
        )
    }

    @Test
    fun `something to watch and nothing running starts it`() {
        assertEquals(
            WatchAction.Start,
            watchAction(wanted = true, running = false, signature = 5, startedWith = 0),
        )
    }

    @Test
    fun `the same watch already running is left alone`() {
        assertEquals(
            WatchAction.LeaveAlone,
            watchAction(wanted = true, running = true, signature = 5, startedWith = 5),
        )
    }

    @Test
    fun `a watch that must now do something else is started again`() {
        assertEquals(
            WatchAction.Start,
            watchAction(wanted = true, running = true, signature = 9, startedWith = 5),
        )
    }

    @Test
    fun `the last rule going stops a running watch`() {
        assertEquals(
            WatchAction.Stop,
            watchAction(wanted = false, running = true, signature = 0, startedWith = 5),
        )
    }

    @Test
    fun `the last rule going stops it even where the signature agrees`() {
        // The hole this exists for. Changing the check rate clears the signature on a service that
        // is still up, so that the next sweep re-registers it. Delete the last rule before that
        // sweep and both are zero — and a watch that only compared them read "nothing to do" at
        // the one moment the answer was "stop", leaving the notification up until process death.
        assertEquals(
            WatchAction.Stop,
            watchAction(wanted = false, running = true, signature = 0, startedWith = 0),
        )
    }
}
