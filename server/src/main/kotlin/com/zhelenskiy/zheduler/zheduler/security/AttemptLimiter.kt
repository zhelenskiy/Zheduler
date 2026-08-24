@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.security

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A fixed-window limiter for sign-in and sign-up attempts.
 *
 * Every attempt is counted twice, under two keys: the caller's address and the account name being
 * tried. The address alone would let one host walk a password list across thousands of accounts;
 * the account name alone would let a botnet hammer one account from a new address each time.
 *
 * Only failures are counted — see [recordFailure] — so somebody using the app normally never
 * approaches the limit however often they sign in.
 */
class AttemptLimiter(
    private val window: Duration,
    private val maxAttempts: Int,
    /** Bounds the table so that made-up keys cannot grow it without limit. */
    private val maxTrackedKeys: Int = 100_000,
) {
    private class Window(var startedAt: Instant, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Whether this key may make another attempt, and if not, how long it has to wait.
     *
     * Null means go ahead.
     */
    fun retryAfter(key: String, now: Instant): Duration? {
        val window = windows[key] ?: return null
        synchronized(window) {
            val elapsed = now - window.startedAt
            if (elapsed >= this.window) return null
            if (window.count < maxAttempts) return null
            return this.window - elapsed
        }
    }

    /** Counts one failed attempt against [key]. */
    fun recordFailure(key: String, now: Instant) {
        // Swept here rather than on a timer, so the class needs no thread of its own; the cost
        // falls on the failure path, which is the rare one.
        if (windows.size >= maxTrackedKeys) makeRoom(now)
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.startedAt >= this.window) {
                window.startedAt = now
                window.count = 1
            } else {
                window.count++
            }
        }
    }

    /** Forgets [key]'s attempts, which is what a successful sign-in earns. */
    fun clear(key: String) {
        windows.remove(key)
    }

    /** How many keys are currently being tracked. Exists so the bound can be tested. */
    internal val trackedKeys: Int get() = windows.size

    private fun makeRoom(now: Instant) {
        windows.entries.removeIf { (_, window) ->
            synchronized(window) { now - window.startedAt >= this.window }
        }
        if (windows.size < maxTrackedKeys) return
        // Every window is still live, which means this many distinct addresses or account names
        // are failing at once. Something has to give, and the least harmful choice is the entries
        // with the least time left to run: they were about to stop limiting anyone anyway.
        val surplus = windows.size - maxTrackedKeys + maxTrackedKeys / 10
        windows.entries
            .sortedBy { (_, window) -> synchronized(window) { window.startedAt } }
            .take(surplus)
            .forEach { (key, _) -> windows.remove(key) }
    }
}
