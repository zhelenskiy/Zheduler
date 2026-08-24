package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OutcomeTest {

    private val failure = Outcome.Failure(RemoteError.TimedOut)

    @Test
    fun `mapping a success transforms the value and leaves it a success`() {
        assertEquals(Outcome.Success(4), Outcome.Success(2).map { it * 2 })
    }

    @Test
    fun `mapping a failure does not run the transform`() {
        var ran = false
        val result = failure.map { ran = true; it }
        assertIs<Outcome.Failure>(result)
        assertEquals(RemoteError.TimedOut, result.error)
        assertTrue(!ran, "a failure must not be transformed as if it held a value")
    }

    @Test
    fun `flatMap chains successes and short-circuits on the first failure`() {
        assertEquals(
            Outcome.Success("2"),
            Outcome.Success(2).flatMap { Outcome.Success(it.toString()) },
        )
        val stopped = Outcome.Success(2)
            .flatMap { Outcome.Failure(RemoteError.NotFound) }
            .flatMap<Any, Any> { error("the second step should never run") }
        assertEquals(Outcome.Failure(RemoteError.NotFound), stopped)
    }

    @Test
    fun `fold takes exactly one branch`() {
        assertEquals("ok:2", Outcome.Success(2).fold({ "ok:$it" }, { "no:${it.message}" }))
        assertEquals("no", failure.fold({ "ok" }, { "no" }))
    }

    @Test
    fun `getOrNull and errorOrNull are opposites`() {
        assertEquals(2, Outcome.Success(2).getOrNull())
        assertNull(Outcome.Success(2).errorOrNull())
        assertNull(failure.getOrNull())
        assertEquals(RemoteError.TimedOut, failure.errorOrNull())
    }

    @Test
    fun `onFailure and onSuccess run only on their own branch and pass the value through`() {
        val seen = mutableListOf<String>()
        val success = Outcome.Success(1)
            .onSuccess { seen += "success" }
            .onFailure { seen += "failure" }
        assertEquals(Outcome.Success(1), success)

        val failed = failure
            .onSuccess { seen += "unexpected success" }
            .onFailure { seen += "failure" }
        assertEquals(failure, failed)
        assertEquals(listOf("success", "failure"), seen)
    }

    @Test
    fun `a transport hiccup offers a retry and a rejected password does not`() {
        assertEquals(RemoteRemedy.Retry, RemoteError.Unreachable("refused").remedy)
        assertEquals(RemoteRemedy.Retry, RemoteError.TimedOut.remedy)
        assertEquals(RemoteRemedy.Retry, RemoteError.ServerFault(503).remedy)
        assertEquals(RemoteRemedy.RetryLater, RemoteError.RateLimited(30.seconds).remedy)
        assertEquals(RemoteRemedy.SignIn, RemoteError.AuthenticationRequired().remedy)
        assertEquals(RemoteRemedy.ResolveConflict, RemoteError.Conflict(3L).remedy)
        assertEquals(RemoteRemedy.None, RemoteError.NotFound.remedy)
        // A refusal on the merits offers no button: what has to change is a field already on
        // screen, and a button pointing elsewhere would point away from it.
        assertEquals(
            RemoteRemedy.None,
            RemoteError.Rejected(ApiErrorCode.InvalidCredentials, "no").remedy,
        )
        assertEquals(
            RemoteRemedy.None,
            RemoteError.Rejected(ApiErrorCode.Internal, "no").remedy,
        )
        // An address that is wrong is not a field in the same form, so it does get one.
        assertEquals(RemoteRemedy.ReviewSettings, RemoteError.InsecureAddress("use https").remedy)
        assertEquals(RemoteRemedy.ReviewSettings, RemoteError.Malformed("not a server").remedy)
    }

    @Test
    fun `every error has something to say to the user`() {
        listOf(
            RemoteError.Unreachable(),
            RemoteError.Unreachable("connection refused"),
            RemoteError.TimedOut,
            RemoteError.ServerFault(500),
            RemoteError.ServerFault(500, "database down"),
            RemoteError.RateLimited(),
            RemoteError.RateLimited(30.seconds),
            RemoteError.AuthenticationRequired(),
            RemoteError.NotAllowed(),
            RemoteError.NotFound,
            RemoteError.Conflict(null),
            RemoteError.Rejected(ApiErrorCode.WeakPassword, "too short"),
            RemoteError.Malformed(),
            RemoteError.InsecureAddress("use https"),
        ).forEach { error ->
            assertTrue(error.message.isNotBlank(), "$error has nothing to show")
        }
    }
}
