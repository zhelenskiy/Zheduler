@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A clock the test moves by hand, so expiry and rate-limit windows do not need real waiting. */
class MutableClock(private var current: Instant = Instant.fromEpochSeconds(1_700_000_000)) : Clock {
    override fun now(): Instant = current

    fun advanceBy(duration: Duration) {
        current += duration
    }
}

/** A password long enough for the server to accept, used wherever the password itself is not the point. */
const val VALID_PASSWORD = "correct horse battery staple"

/** A second one, so "wrong password" tests do not have to invent one each time. */
const val OTHER_PASSWORD = "another perfectly fine passphrase"
