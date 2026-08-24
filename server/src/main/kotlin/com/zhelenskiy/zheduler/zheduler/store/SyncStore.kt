@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.store

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** An account, as storage knows it. The password hash never leaves this layer except to be checked. */
data class StoredAccount(
    val userId: String,
    val username: String,
    val passwordHash: String,
)

/** One space's metadata, without the payload that dwarfs it. */
data class StoredSpaceHeader(
    val remoteId: String,
    val name: String,
    val idPrefix: String,
    val revision: Long,
    val updatedAt: Instant,
    val payloadBytes: Long,
)

data class StoredSpace(
    val header: StoredSpaceHeader,
    val payload: String,
)

/** What a write left behind, when it happened. */
data class WriteReceipt(val revision: Long, val updatedAt: Instant)

/**
 * The outcome of a write guarded by a revision.
 *
 * A conflict is a value rather than an exception because it is an ordinary thing for two devices
 * to do, and because the revision the loser needs in order to recover comes back with it.
 */
sealed interface WriteResult {
    data class Written(val receipt: WriteReceipt) : WriteResult

    /** The stored revision was not the one the caller expected. Null means there is no row. */
    data class Conflict(val currentRevision: Long?) : WriteResult

    data object NotFound : WriteResult
}

sealed interface CreateAccountResult {
    data class Created(val account: StoredAccount) : CreateAccountResult
    data object UsernameTaken : CreateAccountResult
}

/**
 * Everything the server keeps, behind one interface so that the same suite can hold both the
 * in-memory and the PostgreSQL implementation to the same behaviour.
 *
 * Every space method takes a `userId` and every implementation is expected to make it part of the
 * key rather than of a filter. That is the whole of the tenant isolation: there is no way to name
 * another account's space, because a space id on its own does not name anything.
 */
interface SyncStore : AutoCloseable {

    suspend fun createAccount(username: String, passwordHash: String): CreateAccountResult

    suspend fun findAccount(username: String): StoredAccount?

    /** Replaces a stored hash, after signing in with parameters that have since been strengthened. */
    suspend fun updatePasswordHash(userId: String, passwordHash: String)

    suspend fun storeToken(tokenFingerprint: ByteArray, userId: String, issuedAt: Instant, expiresAt: Instant)

    /** The account a live token belongs to. Expired tokens answer null and are cleaned up. */
    suspend fun accountForToken(tokenFingerprint: ByteArray, now: Instant): StoredAccount?

    suspend fun revokeToken(tokenFingerprint: ByteArray)

    /** Drops every token that has passed its expiry. Returns how many. */
    suspend fun purgeExpiredTokens(now: Instant): Int

    suspend fun listSpaces(userId: String): List<StoredSpaceHeader>

    suspend fun loadSpace(userId: String, remoteId: String): StoredSpace?

    /** Just the revision, for answering a conditional request without moving the payload. */
    suspend fun spaceRevision(userId: String, remoteId: String): Long?

    /** Stores a space that must not exist yet. A [WriteResult.Conflict] means it already does. */
    suspend fun createSpace(
        userId: String,
        remoteId: String,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult

    /** Replaces a space, but only if it is still at [expectedRevision]. */
    suspend fun updateSpace(
        userId: String,
        remoteId: String,
        expectedRevision: Long,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult

    suspend fun deleteSpace(userId: String, remoteId: String, expectedRevision: Long): WriteResult

    override fun close() = Unit
}
