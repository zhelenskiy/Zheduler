@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class AttemptLimiterTest {

    private val start = Instant.fromEpochSeconds(1_700_000_000)

    private fun limiter(max: Int = 3, keys: Int = 100_000) =
        AttemptLimiter(window = 10.minutes, maxAttempts = max, maxTrackedKeys = keys)

    @Test
    fun `attempts below the limit are allowed`() {
        val limiter = limiter()
        repeat(2) { limiter.recordFailure("k", start) }
        assertNull(limiter.retryAfter("k", start))
    }

    @Test
    fun `the limit blocks and reports how long is left`() {
        val limiter = limiter()
        repeat(3) { limiter.recordFailure("k", start) }
        val wait = limiter.retryAfter("k", start + 4.minutes)
        assertNotNull(wait)
        assertEquals(6.minutes, wait)
    }

    @Test
    fun `keys do not affect one another`() {
        val limiter = limiter()
        repeat(3) { limiter.recordFailure("one", start) }
        assertNotNull(limiter.retryAfter("one", start))
        assertNull(limiter.retryAfter("two", start))
    }

    @Test
    fun `the window expires`() {
        val limiter = limiter()
        repeat(3) { limiter.recordFailure("k", start) }
        assertNotNull(limiter.retryAfter("k", start))
        assertNull(limiter.retryAfter("k", start + 10.minutes))
    }

    @Test
    fun `a failure after the window starts a fresh count rather than continuing the old one`() {
        val limiter = limiter()
        repeat(3) { limiter.recordFailure("k", start) }
        val later = start + 11.minutes
        limiter.recordFailure("k", later)
        // One failure in the new window, not four carried over, so the caller is not still locked.
        assertNull(limiter.retryAfter("k", later))
    }

    @Test
    fun `success clears the count`() {
        val limiter = limiter()
        repeat(3) { limiter.recordFailure("k", start) }
        limiter.clear("k")
        assertNull(limiter.retryAfter("k", start))
    }

    @Test
    fun `the table does not grow without bound`() {
        val limiter = limiter(keys = 100)
        // Ten times the bound, all within one window, which is the shape of an attack that walks
        // a username list rather than of anybody signing in.
        repeat(1_000) { index -> limiter.recordFailure("key-$index", start) }
        assertTrue(limiter.trackedKeys <= 100, "tracked ${limiter.trackedKeys} keys")
    }

    @Test
    fun `expired entries are what gets dropped first`() {
        val limiter = limiter(keys = 100)
        repeat(100) { index -> limiter.recordFailure("old-$index", start) }
        val later = start + 11.minutes
        repeat(50) { index -> limiter.recordFailure("new-$index", later) }
        // The old windows had run out, so making room for the new ones cost nothing that was
        // still limiting anybody.
        assertTrue(limiter.trackedKeys <= 100)
        repeat(50) { index ->
            limiter.recordFailure("new-$index", later)
            limiter.recordFailure("new-$index", later)
        }
        assertNotNull(limiter.retryAfter("new-0", later + 1.seconds))
    }
}
