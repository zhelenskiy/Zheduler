package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * What a failed remote call puts in front of the user.
 *
 * The offered action comes from the error's remedy, so what is being checked here is that a wrong
 * password does not get a "Try again" button — repeating an identical request that was refused on
 * its merits is the offer that trains people to press a button that cannot work.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteFailureTest {

    @Test
    fun aTransportFailureOffersARetryAndRunsIt() = runComposeUiTest {
        var retries = 0
        setContent {
            RemoteFailure(error = RemoteError.Unreachable("refused"), onRetry = { retries++ })
        }
        waitForIdle()

        onNodeWithText("Could not reach the server: refused").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        waitForIdle()

        assertEquals(1, retries)
    }

    @Test
    fun aRetryAlreadyInFlightCannotBeStartedAgain() = runComposeUiTest {
        var retries = 0
        setContent {
            RemoteFailure(
                error = RemoteError.TimedOut,
                onRetry = { retries++ },
                isRetrying = true,
            )
        }
        waitForIdle()

        onNodeWithText("Try again").assertIsNotEnabled()
        assertEquals(0, retries)
    }

    @Test
    fun anExpiredSessionOffersSigningInRatherThanRetrying() = runComposeUiTest {
        var signIns = 0
        var retries = 0
        setContent {
            RemoteFailure(
                error = RemoteError.AuthenticationRequired("Your session has ended."),
                onRetry = { retries++ },
                onSignIn = { signIns++ },
            )
        }
        waitForIdle()

        onNodeWithText("Your session has ended.").assertIsDisplayed()
        onNodeWithText("Sign in").performClick()
        waitForIdle()

        assertEquals(1, signIns)
        assertEquals(0, retries, "an expired token is not something a retry can fix")
    }

    @Test
    fun aConflictOffersResolvingItRatherThanRetrying() = runComposeUiTest {
        // Retrying a conflict would send the same stale revision and be refused again.
        var resolves = 0
        var retries = 0
        setContent {
            RemoteFailure(
                error = RemoteError.Conflict(7L),
                onRetry = { retries++ },
                onResolveConflict = { resolves++ },
            )
        }
        waitForIdle()

        onNodeWithText("Resolve").performClick()
        waitForIdle()

        assertEquals(1, resolves)
        assertEquals(0, retries)
    }

    @Test
    fun aRejectedPasswordOffersNoRetryAtAll() = runComposeUiTest {
        setContent {
            RemoteFailure(
                error = RemoteError.Rejected(ApiErrorCode.InvalidCredentials, "Those do not match."),
                onRetry = {},
            )
        }
        waitForIdle()

        onNodeWithText("Those do not match.").assertIsDisplayed()
        // No "Change settings" handler was given, so the sentence is the whole answer rather than
        // a button that does nothing.
        onNodeWithText("Try again").assertDoesNotExist()
        onNodeWithText("Change settings").assertDoesNotExist()
    }

    @Test
    fun anErrorNothingCanBeDoneAboutShowsNoButton() = runComposeUiTest {
        setContent {
            RemoteFailure(error = RemoteError.NotFound, onRetry = {})
        }
        waitForIdle()

        onNodeWithText("The server does not have that space any more.").assertIsDisplayed()
        onNodeWithText("Try again").assertDoesNotExist()
        onNodeWithText("Sign in").assertDoesNotExist()
        onNodeWithText("Resolve").assertDoesNotExist()
    }

    @Test
    fun aRateLimitSaysHowLongToWait() = runComposeUiTest {
        setContent {
            RemoteFailure(error = RemoteError.RateLimited(90.seconds), onRetry = {})
        }
        waitForIdle()

        onNodeWithText("Too many attempts. Try again in 1m 30s.").assertIsDisplayed()
    }

    @Test
    fun aHandlerThatWasNotGivenLeavesNoDeadButtonBehind() = runComposeUiTest {
        // The conflict's remedy is "resolve", but this screen has nowhere to send that.
        setContent {
            RemoteFailure(error = RemoteError.Conflict(3L), onRetry = {})
        }
        waitForIdle()

        onNodeWithText("Resolve").assertDoesNotExist()
        onNodeWithText("This space was changed elsewhere since it was last downloaded.")
            .assertIsDisplayed()
    }
}

