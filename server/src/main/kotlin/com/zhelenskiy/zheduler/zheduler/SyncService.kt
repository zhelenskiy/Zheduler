@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.security.AttemptLimiter
import com.zhelenskiy.zheduler.zheduler.security.Passwords
import com.zhelenskiy.zheduler.zheduler.security.Tokens
import com.zhelenskiy.zheduler.zheduler.store.CreateAccountResult
import com.zhelenskiy.zheduler.zheduler.store.StoredAccount
import com.zhelenskiy.zheduler.zheduler.store.StoredSpace
import com.zhelenskiy.zheduler.zheduler.store.StoredSpaceHeader
import com.zhelenskiy.zheduler.zheduler.store.SyncStore
import com.zhelenskiy.zheduler.zheduler.store.WriteResult
import com.zhelenskiy.zheduler.zheduler.sync.AccountInfo
import com.zhelenskiy.zheduler.zheduler.sync.ApiError
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthResponse
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushRequest
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushResponse
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSummary
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * What an endpoint got, before it has been turned into a response.
 *
 * A refusal carries the status alongside the body because the two are chosen together, and
 * splitting them is how a 404 ends up carrying a "conflict" code.
 */
sealed interface ServiceResult<out T> {
    data class Ok<out T>(val value: T) : ServiceResult<T>
    data class Failed(
        val status: HttpStatusCode,
        val error: ApiError,
        val retryAfter: Duration? = null,
    ) : ServiceResult<Nothing>
}

/**
 * The three counters that stand between an anonymous caller and the password hashing.
 *
 * Separate rather than one shared limiter because they answer different questions, and a single
 * threshold cannot: how many times one *account* may be guessed at, how many failures one
 * *address* may produce across all accounts, and how many accounts one address may create.
 */
class RateLimits(
    val perAccount: AttemptLimiter,
    val perAddress: AttemptLimiter,
    val signUps: AttemptLimiter,
) {
    companion object {
        /** The limits the server runs with. */
        fun standard(): RateLimits = RateLimits(
            perAccount = AttemptLimiter(
                window = ServerConfig.AUTH_RATE_WINDOW,
                maxAttempts = ServerConfig.AUTH_ATTEMPTS_PER_WINDOW,
            ),
            perAddress = AttemptLimiter(
                window = ServerConfig.AUTH_RATE_WINDOW,
                maxAttempts = ServerConfig.AUTH_ATTEMPTS_PER_ADDRESS_PER_WINDOW,
            ),
            signUps = AttemptLimiter(
                window = ServerConfig.AUTH_RATE_WINDOW,
                maxAttempts = ServerConfig.SIGN_UPS_PER_ADDRESS_PER_WINDOW,
            ),
        )
    }
}

/** A downloaded space, or the news that the caller already has the current one. */
sealed interface SpaceRead {
    data class Current(val space: StoredSpace) : SpaceRead
    data class Unchanged(val revision: Long) : SpaceRead
}

/**
 * Everything the server does, with HTTP left to the routes and storage left to the store.
 *
 * Kept separate from both so that the rules that matter — what a valid username is, when a
 * password is rehashed, which failures are counted against a rate limit — can be tested without
 * a socket or a database.
 */
class SyncService(
    private val store: SyncStore,
    private val clock: Clock,
    private val tokenLifetime: Duration,
    private val limits: RateLimits,
    /**
     * Where password hashing runs.
     *
     * Deriving a PBKDF2 hash is a fifth of a second of CPU on purpose, and doing it on a Netty
     * worker would stall every other request that thread is carrying. Bounded to the number of
     * cores because past that the work does not go faster, it only queues somewhere less visible.
     */
    private val hashing: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(Runtime.getRuntime().availableProcessors()),
) {

    // ---------------------------------------------------------------- accounts

    suspend fun register(
        username: String,
        password: String,
        clientKey: String,
    ): ServiceResult<AuthResponse> {
        val normalized = normalizeUsername(username)
        validateUsername(normalized)?.let { return it }
        validatePassword(password)?.let { return it }

        val now = clock.now()
        // Counted before the hash, and counted whether or not this one works. An account costs
        // the caller nothing and the server a deliberate fifth of a second of PBKDF2, so a
        // success that reset the counter would leave account creation unlimited — and with it a
        // way to spend every core the server has from one address.
        limits.signUps.retryAfter(addressKey(clientKey), now)?.let { return tooManyAttempts(it) }
        limits.signUps.recordFailure(addressKey(clientKey), now)

        limitedOut(clientKey, normalized)?.let { return it }

        val hash = withContext(hashing) { Passwords.hash(password) }
        return when (val created = store.createAccount(normalized, hash)) {
            is CreateAccountResult.UsernameTaken -> {
                // Counted, because trying names until one is taken is how an account list is
                // built; the limiter is what makes that slow.
                recordFailure(clientKey, normalized)
                ServiceResult.Failed(
                    HttpStatusCode.Conflict,
                    ApiError(ApiErrorCode.UsernameTaken, "That username is already taken."),
                )
            }

            is CreateAccountResult.Created -> {
                clearAccountFailures(normalized)
                ServiceResult.Ok(issueToken(created.account))
            }
        }
    }

    suspend fun logIn(
        username: String,
        password: String,
        clientKey: String,
    ): ServiceResult<AuthResponse> {
        val normalized = normalizeUsername(username)
        // Not validated the way registration validates: a sign-in with a malformed username is
        // simply wrong credentials, and saying otherwise would tell a caller which names could
        // exist without them having to try one.
        // A NUL is refused here too, and as wrong credentials rather than as a bad request: no
        // account can contain one, and letting it reach the store means PostgreSQL throwing on a
        // TEXT parameter — a 500 and a log line that any unauthenticated caller can produce at
        // will, and one that skips the failure count on the way out.
        if (normalized.length > SyncProtocol.MAX_USERNAME_LENGTH ||
            password.length > SyncProtocol.MAX_PASSWORD_LENGTH ||
            NUL in normalized
        ) {
            return invalidCredentials()
        }

        limitedOut(clientKey, normalized)?.let { return it }

        val account = store.findAccount(normalized)
        val verified = withContext(hashing) {
            if (account == null) Passwords.verifyDecoy(password) else Passwords.verify(password, account.passwordHash)
        }
        if (account == null || !verified) {
            recordFailure(clientKey, normalized)
            return invalidCredentials()
        }

        if (Passwords.needsRehash(account.passwordHash)) {
            // The one moment the plaintext is in hand and the account is known to be the right
            // one, so it is the only moment an old hash can be strengthened.
            val stronger = withContext(hashing) { Passwords.hash(password) }
            store.updatePasswordHash(account.userId, stronger)
        }

        // Only this account's own counter. The address counter is shared with every other account
        // tried from here, and clearing it on one success is exactly how a spray gets its budget
        // back: eleven failures, one throwaway sign-in, eleven more, indefinitely.
        clearAccountFailures(normalized)
        return ServiceResult.Ok(issueToken(account))
    }

    /** The account a bearer token belongs to, or the refusal to hand back. */
    suspend fun authenticate(authorizationHeader: String?): ServiceResult<StoredAccount> {
        val token = Tokens.fromAuthorizationHeader(authorizationHeader)
            ?: return unauthenticated("Sign in to continue.")
        val account = store.accountForToken(Tokens.fingerprint(token), clock.now())
            ?: return unauthenticated("Your session has expired. Sign in again.")
        return ServiceResult.Ok(account)
    }

    suspend fun logOut(authorizationHeader: String?) {
        val token = Tokens.fromAuthorizationHeader(authorizationHeader) ?: return
        store.revokeToken(Tokens.fingerprint(token))
    }

    fun accountInfo(account: StoredAccount): AccountInfo =
        AccountInfo(userId = account.userId, username = account.username)

    suspend fun purgeExpiredTokens(): Int = store.purgeExpiredTokens(clock.now())

    // ------------------------------------------------------------------ spaces

    suspend fun listSpaces(account: StoredAccount): List<SpaceSummary> =
        store.listSpaces(account.userId).map { header -> header.toSummary() }

    suspend fun readSpace(
        account: StoredAccount,
        remoteId: String,
        knownRevision: Long?,
    ): ServiceResult<SpaceRead> {
        validateRemoteId(remoteId)?.let { return it }

        if (knownRevision != null) {
            // Answered from the revision alone: a space the caller already has must not be read
            // off the disk, let alone put on the wire.
            val current = store.spaceRevision(account.userId, remoteId)
                ?: return notFound()
            if (current == knownRevision) return ServiceResult.Ok(SpaceRead.Unchanged(current))
        }

        val space = store.loadSpace(account.userId, remoteId) ?: return notFound()
        return ServiceResult.Ok(SpaceRead.Current(space))
    }

    suspend fun createSpace(
        account: StoredAccount,
        remoteId: String,
        request: SpacePushRequest,
    ): ServiceResult<SpacePushResponse> {
        validateRemoteId(remoteId)?.let { return it }
        validatePush(request)?.let { return it }

        val result = store.createSpace(
            userId = account.userId,
            remoteId = remoteId,
            name = request.name,
            idPrefix = request.idPrefix,
            payload = request.payload,
            now = clock.now(),
        )
        return result.toResponse(remoteId)
    }

    suspend fun updateSpace(
        account: StoredAccount,
        remoteId: String,
        expectedRevision: Long,
        request: SpacePushRequest,
    ): ServiceResult<SpacePushResponse> {
        validateRemoteId(remoteId)?.let { return it }
        validatePush(request)?.let { return it }

        val result = store.updateSpace(
            userId = account.userId,
            remoteId = remoteId,
            expectedRevision = expectedRevision,
            name = request.name,
            idPrefix = request.idPrefix,
            payload = request.payload,
            now = clock.now(),
        )
        return result.toResponse(remoteId)
    }

    suspend fun deleteSpace(
        account: StoredAccount,
        remoteId: String,
        expectedRevision: Long,
    ): ServiceResult<Unit> {
        validateRemoteId(remoteId)?.let { return it }
        return when (val result = store.deleteSpace(account.userId, remoteId, expectedRevision)) {
            is WriteResult.Written -> ServiceResult.Ok(Unit)
            is WriteResult.NotFound -> notFound()
            is WriteResult.Conflict -> conflict(result.currentRevision)
        }
    }

    // ------------------------------------------------------------- the details

    private suspend fun issueToken(account: StoredAccount): AuthResponse {
        val issuedAt = clock.now()
        val expiresAt = issuedAt + tokenLifetime
        val token = Tokens.mint()
        store.storeToken(Tokens.fingerprint(token), account.userId, issuedAt, expiresAt)
        return AuthResponse(
            token = token,
            userId = account.userId,
            username = account.username,
            expiresAtEpochSeconds = expiresAt.epochSeconds,
        )
    }

    private fun WriteResult.toResponse(remoteId: String): ServiceResult<SpacePushResponse> = when (this) {
        is WriteResult.Written -> ServiceResult.Ok(
            SpacePushResponse(
                remoteId = remoteId,
                revision = receipt.revision,
                updatedAtEpochSeconds = receipt.updatedAt.epochSeconds,
            )
        )

        is WriteResult.NotFound -> notFound()
        is WriteResult.Conflict -> conflict(currentRevision)
    }

    private fun limitedOut(clientKey: String, username: String): ServiceResult.Failed? {
        val now = clock.now()
        val wait = listOfNotNull(
            limits.perAddress.retryAfter(addressKey(clientKey), now),
            limits.perAccount.retryAfter(accountKey(username), now),
        ).maxOrNull() ?: return null
        return tooManyAttempts(wait)
    }

    private fun recordFailure(clientKey: String, username: String) {
        val now = clock.now()
        limits.perAddress.recordFailure(addressKey(clientKey), now)
        limits.perAccount.recordFailure(accountKey(username), now)
    }

    private fun clearAccountFailures(username: String) {
        limits.perAccount.clear(accountKey(username))
    }

    private fun validateUsername(username: String): ServiceResult.Failed? = when {
        username.length < SyncProtocol.MIN_USERNAME_LENGTH ->
            invalidRequest("A username needs at least ${SyncProtocol.MIN_USERNAME_LENGTH} characters.")

        username.length > SyncProtocol.MAX_USERNAME_LENGTH ->
            invalidRequest("A username may be at most ${SyncProtocol.MAX_USERNAME_LENGTH} characters.")

        !SyncProtocol.USERNAME_PATTERN.matches(username) -> invalidRequest(
            "A username may contain letters, digits, dots, dashes and underscores, " +
                "and must start and end with a letter or a digit."
        )

        else -> null
    }

    private fun validatePassword(password: String): ServiceResult.Failed? = when {
        password.length < SyncProtocol.MIN_PASSWORD_LENGTH -> ServiceResult.Failed(
            HttpStatusCode.BadRequest,
            ApiError(
                ApiErrorCode.WeakPassword,
                "A password needs at least ${SyncProtocol.MIN_PASSWORD_LENGTH} characters.",
            ),
        )

        password.length > SyncProtocol.MAX_PASSWORD_LENGTH -> ServiceResult.Failed(
            HttpStatusCode.BadRequest,
            ApiError(
                ApiErrorCode.WeakPassword,
                "A password may be at most ${SyncProtocol.MAX_PASSWORD_LENGTH} characters.",
            ),
        )

        else -> null
    }

    private fun validateRemoteId(remoteId: String): ServiceResult.Failed? = when {
        remoteId.isEmpty() || remoteId.length > MAX_REMOTE_ID_LENGTH ->
            invalidRequest("That is not a usable space identifier.")

        !REMOTE_ID_PATTERN.matches(remoteId) ->
            invalidRequest("A space identifier may contain letters, digits, dots, dashes and underscores.")

        else -> null
    }

    private fun validatePush(request: SpacePushRequest): ServiceResult.Failed? {
        if (request.name.isBlank()) return invalidRequest("A space needs a name.")
        if (request.name.length > SyncProtocol.MAX_SPACE_NAME_LENGTH) {
            return invalidRequest("That space name is too long.")
        }
        if (!ID_PREFIX_PATTERN.matches(request.idPrefix)) {
            return invalidRequest("A space's id prefix must be uppercase letters.")
        }
        // PostgreSQL cannot put a NUL byte in a TEXT column, so one reaching the store is a 500
        // there and a cheerful 201 in memory — the two implementations disagreeing about what can
        // be stored. Refused here, where both see the same answer. A space this app writes never
        // contains one; a request that does was not written by this app.
        if (NUL in request.name || NUL in request.payload) {
            return invalidRequest("A space may not contain a NUL character.")
        }
        // Counted in bytes, which is what the storage and the wire both charge for; a payload of
        // emoji is four times the length of its character count.
        val bytes = request.payload.toByteArray(Charsets.UTF_8).size
        if (bytes > SyncProtocol.MAX_PAYLOAD_BYTES) {
            return ServiceResult.Failed(
                ContentTooLargeStatus,
                ApiError(
                    ApiErrorCode.PayloadTooLarge,
                    "That space is larger than the ${SyncProtocol.MAX_PAYLOAD_BYTES / (1024 * 1024)} MB " +
                        "this server accepts.",
                ),
            )
        }
        return null
    }

    private companion object {
        const val MAX_REMOTE_ID_LENGTH = 128
        const val NUL = '\u0000'
        val REMOTE_ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")
        val ID_PREFIX_PATTERN = Regex("^[A-Z]{1,32}$")

        fun normalizeUsername(raw: String): String = raw.trim().lowercase()

        fun addressKey(clientKey: String) = "addr:$clientKey"
        fun accountKey(username: String) = "user:$username"

        fun StoredSpaceHeader.toSummary() = SpaceSummary(
            remoteId = remoteId,
            name = name,
            idPrefix = idPrefix,
            revision = revision,
            updatedAtEpochSeconds = updatedAt.epochSeconds,
            payloadBytes = payloadBytes,
        )

        fun tooManyAttempts(wait: Duration) = ServiceResult.Failed(
            HttpStatusCode.TooManyRequests,
            ApiError(ApiErrorCode.RateLimited, "Too many attempts. Try again later."),
            retryAfter = wait,
        )

        fun invalidRequest(message: String) = ServiceResult.Failed(
            HttpStatusCode.BadRequest,
            ApiError(ApiErrorCode.InvalidRequest, message),
        )

        fun invalidCredentials() = ServiceResult.Failed(
            HttpStatusCode.Unauthorized,
            // One message for "no such account" and for "wrong password", so that the answer
            // never says which usernames exist.
            ApiError(ApiErrorCode.InvalidCredentials, "That username and password do not match."),
        )

        fun unauthenticated(message: String) = ServiceResult.Failed(
            HttpStatusCode.Unauthorized,
            ApiError(ApiErrorCode.Unauthenticated, message),
        )

        fun notFound() = ServiceResult.Failed(
            HttpStatusCode.NotFound,
            ApiError(ApiErrorCode.NotFound, "There is no such space on this server."),
        )

        fun conflict(currentRevision: Long?) = ServiceResult.Failed(
            HttpStatusCode.PreconditionFailed,
            ApiError(
                ApiErrorCode.RevisionMismatch,
                "This space has been changed since that copy was downloaded.",
                currentRevision = currentRevision,
            ),
        )
    }
}
