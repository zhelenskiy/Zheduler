@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import com.zhelenskiy.zheduler.zheduler.sync.AccountKey
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaceStatus
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSpaceLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Which single problem a space's row shows.
 *
 * Written for a complaint: signing out of a space whose session had already expired left two
 * "Sign in again" buttons under it — the status said the session was gone, and the failed sign-out
 * said so again. One space with one thing wrong with it gets one thing to press.
 */
class SpaceProblemTest {

    private val link = RemoteSpaceLink(
        spaceId = "space-1",
        account = AccountKey("https://sync.example.com", "ada"),
        remoteSpaceId = "remote-1",
        lastSyncedRevision = 3,
        lastSyncedAtEpochSeconds = 1_700_000_000,
    )

    private val sessionGone = RemoteError.AuthenticationRequired()
    private val noRoute = RemoteError.Unreachable("no route")

    @Test
    fun `a blocked space shows its own trouble and not the action's echo of it`() {
        val shown = spaceProblem(CloudSpaceStatus.Blocked(link, sessionGone), failure = sessionGone)

        assertEquals(sessionGone, shown)
    }

    @Test
    fun `an offline space shows its own trouble even when an action reported another`() {
        // The row is about where this space stands, which is what the status holds. An action's
        // leftover message underneath it is the same problem worded differently, or an older one.
        val shown = spaceProblem(CloudSpaceStatus.Offline(link, noRoute, asOf = null), failure = sessionGone)

        assertEquals(noRoute, shown)
    }

    @Test
    fun `a healthy space still shows what an action reported`() {
        // Deleting on the server can fail while the space itself is perfectly in step; that
        // failure has nowhere else to be said.
        val shown = spaceProblem(CloudSpaceStatus.Live(link), failure = noRoute)

        assertEquals(noRoute, shown)
    }

    @Test
    fun `a space with nothing wrong shows nothing`() {
        assertNull(spaceProblem(CloudSpaceStatus.Live(link), failure = null))
        assertNull(spaceProblem(CloudSpaceStatus.OnThisDevice, failure = null))
        assertNull(
            spaceProblem(
                CloudSpaceStatus.Saving(link.copy(lastSyncedAtEpochSeconds = Instant.DISTANT_PAST.epochSeconds)),
                failure = null,
            )
        )
    }
}
