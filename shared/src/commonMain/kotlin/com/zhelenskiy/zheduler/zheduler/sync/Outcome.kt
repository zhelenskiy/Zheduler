@file:MustUseReturnValues

package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.time.Duration

/**
 * The result of something that had to cross the network to happen.
 *
 * Every remote call returns one of these rather than the value itself, so that "it did not arrive"
 * is a case the caller has to name. The file is marked [MustUseReturnValues], which makes the
 * compiler warn when one of these is produced and dropped — a failure that is never looked at is
 * the same bug as a failure that is never reported.
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: RemoteError) : Outcome<Nothing>
}

/** The value, or `null` if the call failed. The only way to discard a [RemoteError] by name. */
fun <T> Outcome<T>.getOrNull(): T? = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> null
}

/** The error, or `null` if the call succeeded. */
fun <T> Outcome<T>.errorOrNull(): RemoteError? = when (this) {
    is Outcome.Success -> null
    is Outcome.Failure -> error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (RemoteError) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

@IgnorableReturnValue
inline fun <T> Outcome<T>.onFailure(action: (RemoteError) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Failure) action(error)
}

@IgnorableReturnValue
inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Success) action(value)
}

/**
 * What the user can do about a [RemoteError], and therefore which button the failure is drawn with.
 *
 * Kept apart from the error itself because the same remedy fits several errors, and because the UI
 * has no business matching on the whole hierarchy to decide between "Retry" and "Sign in".
 */
enum class RemoteRemedy {
    /** Repeating the identical request could work: the network or the server was momentarily out. */
    Retry,

    /** Repeating it would fail the same way until the user waits out a rate limit. */
    RetryLater,

    /** The credentials are gone or expired; nothing works again until the user signs in. */
    SignIn,

    /** Somebody else changed the same space; the user has to choose which copy wins. */
    ResolveConflict,

    /** The address or the account settings are wrong, and only the user can correct them. */
    ReviewSettings,

    /** Nothing the user does from here helps. */
    None,
}

/**
 * Why a remote call did not produce a value.
 *
 * The distinction that matters throughout is [remedy]: a transport hiccup and a rejected password
 * are both "it failed", but only one of them is worth a Retry button.
 */
sealed interface RemoteError {

    /** A sentence to put in front of the user. Never contains a token, a password or a payload. */
    val message: String

    val remedy: RemoteRemedy

    /** Nothing answered at that address: no route, refused connection, TLS failure, DNS. */
    data class Unreachable(val detail: String? = null) : RemoteError {
        override val message: String
            get() = "Could not reach the server" + (detail?.let { ": $it" } ?: ".")
        override val remedy: RemoteRemedy get() = RemoteRemedy.Retry
    }

    /** The server accepted the connection and then took too long. */
    data object TimedOut : RemoteError {
        override val message: String get() = "The server took too long to answer."
        override val remedy: RemoteRemedy get() = RemoteRemedy.Retry
    }

    /** The server broke on its own side (5xx). Repeating the same request is legitimate. */
    data class ServerFault(val status: Int, val detail: String? = null) : RemoteError {
        override val message: String
            get() = "The server reported a problem (HTTP $status)" + (detail?.let { ": $it" } ?: ".")
        override val remedy: RemoteRemedy get() = RemoteRemedy.Retry
    }

    /** Too many requests. [retryAfter] is what the server asked for, when it said. */
    data class RateLimited(val retryAfter: Duration? = null) : RemoteError {
        override val message: String
            get() = "Too many attempts. " + (retryAfter?.let { "Try again in $it." } ?: "Try again shortly.")
        override val remedy: RemoteRemedy get() = RemoteRemedy.RetryLater
    }

    /** No usable credentials: never signed in, signed out elsewhere, or the token expired. */
    data class AuthenticationRequired(val detail: String? = null) : RemoteError {
        override val message: String get() = detail ?: "Sign in to continue."
        override val remedy: RemoteRemedy get() = RemoteRemedy.SignIn
    }

    /** Signed in, but this account may not touch that. */
    data class NotAllowed(val detail: String? = null) : RemoteError {
        override val message: String get() = detail ?: "This account is not allowed to do that."
        override val remedy: RemoteRemedy get() = RemoteRemedy.None
    }

    /** The server has no such space — deleted from another device, most likely. */
    data object NotFound : RemoteError {
        override val message: String get() = "The server does not have that space any more."
        override val remedy: RemoteRemedy get() = RemoteRemedy.None
    }

    /**
     * The copy on the server has moved on since the one being written was read.
     *
     * [remoteRevision] is what the server holds now, so an overwrite can be retried against it
     * rather than blindly.
     */
    data class Conflict(val remoteRevision: Long?) : RemoteError {
        override val message: String
            get() = "This space was changed elsewhere since it was last downloaded."
        override val remedy: RemoteRemedy get() = RemoteRemedy.ResolveConflict
    }

    /**
     * The request was refused for a reason the server named, and repeating it will not help.
     *
     * The remedy is [RemoteRemedy.None] throughout — not because nothing can be done, but because
     * what has to be done is to correct a field that is already on screen. A button next to a
     * rejected password saying "Change settings" points away from the password field two lines
     * above it.
     */
    data class Rejected(val code: ApiErrorCode, override val message: String) : RemoteError {
        override val remedy: RemoteRemedy get() = RemoteRemedy.None
    }

    /** Something answered, but not with what this protocol version expects. */
    data class Malformed(val detail: String? = null) : RemoteError {
        override val message: String
            get() = "The server's answer could not be understood" + (detail?.let { ": $it" } ?: ".")
        override val remedy: RemoteRemedy get() = RemoteRemedy.ReviewSettings
    }

    /**
     * Refused before anything left the device, because the address would have carried the
     * password or the token in the clear.
     */
    data class InsecureAddress(val reason: String) : RemoteError {
        override val message: String get() = reason
        override val remedy: RemoteRemedy get() = RemoteRemedy.ReviewSettings
    }
}

/**
 * Throws the outcome away on purpose.
 *
 * The reason this exists rather than a bare call: the return-value checker is on across this
 * module precisely so that a network failure cannot be dropped by accident, and the handful of
 * places where dropping one is the right answer should have to say so.
 */
fun Outcome<*>.deliberatelyIgnored() = Unit
