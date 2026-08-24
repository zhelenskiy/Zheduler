@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The store with no disk behind it, for development and for the tests that do not need a database.
 *
 * One mutex around everything. That is not how a real store should serialise, but it makes the
 * atomicity that the contract requires — read-then-write under a revision guard — obvious rather
 * than argued, and this implementation is never asked to scale.
 */
class InMemorySyncStore : SyncStore {

    private class Row(
        var name: String,
        var idPrefix: String,
        var payload: String,
        var revision: Long,
        var updatedAt: Instant,
    )

    private class TokenRow(val userId: String, val expiresAt: Instant)

    private val mutex = Mutex()
    private val accountsByUsername = mutableMapOf<String, StoredAccount>()
    private val accountsById = mutableMapOf<String, StoredAccount>()

    /** Keyed by the token's fingerprint as a hex string: a ByteArray is not a usable map key. */
    private val tokens = mutableMapOf<String, TokenRow>()

    /** Keyed by owner first, exactly as the database's primary key is. */
    private val spaces = mutableMapOf<String, MutableMap<String, Row>>()

    override suspend fun createAccount(username: String, passwordHash: String): CreateAccountResult =
        mutex.withLock {
            if (username in accountsByUsername) return@withLock CreateAccountResult.UsernameTaken
            val account = StoredAccount(UUID.randomUUID().toString(), username, passwordHash)
            accountsByUsername[username] = account
            accountsById[account.userId] = account
            CreateAccountResult.Created(account)
        }

    override suspend fun findAccount(username: String): StoredAccount? =
        mutex.withLock { accountsByUsername[username] }

    override suspend fun updatePasswordHash(userId: String, passwordHash: String) = mutex.withLock {
        val existing = accountsById[userId] ?: return@withLock
        val updated = existing.copy(passwordHash = passwordHash)
        accountsById[userId] = updated
        accountsByUsername[updated.username] = updated
    }

    override suspend fun storeToken(
        tokenFingerprint: ByteArray,
        userId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ) = mutex.withLock {
        tokens[tokenFingerprint.toHex()] = TokenRow(userId, expiresAt)
    }

    override suspend fun accountForToken(tokenFingerprint: ByteArray, now: Instant): StoredAccount? =
        mutex.withLock {
            val key = tokenFingerprint.toHex()
            val row = tokens[key] ?: return@withLock null
            if (row.expiresAt <= now) {
                tokens.remove(key)
                return@withLock null
            }
            accountsById[row.userId]
        }

    override suspend fun revokeToken(tokenFingerprint: ByteArray) = mutex.withLock {
        tokens.remove(tokenFingerprint.toHex())
        Unit
    }

    override suspend fun purgeExpiredTokens(now: Instant): Int = mutex.withLock {
        val expired = tokens.filterValues { it.expiresAt <= now }.keys.toList()
        expired.forEach(tokens::remove)
        expired.size
    }

    override suspend fun listSpaces(userId: String): List<StoredSpaceHeader> = mutex.withLock {
        spaces[userId].orEmpty().map { (remoteId, row) -> row.header(remoteId) }.sortedBy { it.remoteId }
    }

    override suspend fun loadSpace(userId: String, remoteId: String): StoredSpace? = mutex.withLock {
        val row = spaces[userId]?.get(remoteId) ?: return@withLock null
        StoredSpace(row.header(remoteId), row.payload)
    }

    override suspend fun spaceRevision(userId: String, remoteId: String): Long? =
        mutex.withLock { spaces[userId]?.get(remoteId)?.revision }

    override suspend fun createSpace(
        userId: String,
        remoteId: String,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult = mutex.withLock {
        val owned = spaces.getOrPut(userId) { mutableMapOf() }
        val existing = owned[remoteId]
        if (existing != null) return@withLock WriteResult.Conflict(existing.revision)
        owned[remoteId] = Row(name, idPrefix, payload, FIRST_REVISION, now)
        WriteResult.Written(WriteReceipt(FIRST_REVISION, now))
    }

    override suspend fun updateSpace(
        userId: String,
        remoteId: String,
        expectedRevision: Long,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult = mutex.withLock {
        val row = spaces[userId]?.get(remoteId) ?: return@withLock WriteResult.NotFound
        if (row.revision != expectedRevision) return@withLock WriteResult.Conflict(row.revision)
        row.name = name
        row.idPrefix = idPrefix
        row.payload = payload
        row.revision += 1
        row.updatedAt = now
        WriteResult.Written(WriteReceipt(row.revision, now))
    }

    override suspend fun deleteSpace(
        userId: String,
        remoteId: String,
        expectedRevision: Long,
    ): WriteResult = mutex.withLock {
        val owned = spaces[userId] ?: return@withLock WriteResult.NotFound
        val row = owned[remoteId] ?: return@withLock WriteResult.NotFound
        if (row.revision != expectedRevision) return@withLock WriteResult.Conflict(row.revision)
        owned.remove(remoteId)
        WriteResult.Written(WriteReceipt(row.revision, row.updatedAt))
    }

    private fun Row.header(remoteId: String) = StoredSpaceHeader(
        remoteId = remoteId,
        name = name,
        idPrefix = idPrefix,
        revision = revision,
        updatedAt = updatedAt,
        // The wire counts bytes, and a payload full of non-ASCII has more of them than characters.
        payloadBytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
    )

    private companion object {
        const val FIRST_REVISION = 1L

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0xF]
        }
    }
}
