@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.zhelenskiy.zheduler.zheduler.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zhelenskiy.zheduler.zheduler.StorageConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The store that survives a restart.
 *
 * Two properties are load-bearing and both live in the schema rather than in this code:
 *
 * - `spaces` is keyed on `(user_id, remote_id)`. A space id on its own addresses nothing, so no
 *   query in this file can be made to cross between accounts by forgetting a clause.
 * - Every write is a single conditional statement with `RETURNING`, so the check on the revision
 *   and the write that depends on it are one operation. There is no window between them for a
 *   second device to slip through, and no row lock held across a round trip.
 */
class PostgresSyncStore(
    private val dataSource: DataSource,
    private val closeDataSource: Boolean,
    poolSize: Int,
) : SyncStore {

    /**
     * JDBC blocks, so every statement runs off the event loop — and no more of them at once than
     * there are connections to run them on.
     *
     * Without the bound, sixty-four IO threads would queue inside Hikari for ten connections and
     * the ones at the back would eventually be failed with a connection timeout. Waiting here
     * instead is the same wait, reported as back-pressure rather than as an error.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(poolSize)

    init {
        // Schema first, so a fresh database is usable with no separate migration step and a
        // restart against an existing one is a no-op.
        useConnection { connection -> PostgresSchema.apply(connection) }
    }

    override suspend fun createAccount(username: String, passwordHash: String): CreateAccountResult =
        query { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (id, username, password_hash, created_at)
                VALUES (?::uuid, ?, ?, ?)
                ON CONFLICT (username) DO NOTHING
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                val id = UUID.randomUUID().toString()
                statement.setString(1, id)
                statement.setString(2, username)
                statement.setString(3, passwordHash)
                statement.setObject(4, OffsetDateTime.now(ZoneOffset.UTC))
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        CreateAccountResult.UsernameTaken
                    } else {
                        CreateAccountResult.Created(
                            StoredAccount(rows.getString(1), username, passwordHash)
                        )
                    }
                }
            }
        }

    override suspend fun findAccount(username: String): StoredAccount? = query { connection ->
        connection.prepareStatement(
            "SELECT id, username, password_hash FROM users WHERE username = ?"
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toAccount() else null
            }
        }
    }

    override suspend fun updatePasswordHash(userId: String, passwordHash: String) {
        query { connection ->
            connection.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE id = ?::uuid"
            ).use { statement ->
                statement.setString(1, passwordHash)
                statement.setString(2, userId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun storeToken(
        tokenFingerprint: ByteArray,
        userId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ) {
        query { connection ->
            connection.prepareStatement(
                """
                INSERT INTO auth_tokens (token_hash, user_id, issued_at, expires_at)
                VALUES (?, ?::uuid, ?, ?)
                ON CONFLICT (token_hash) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, tokenFingerprint)
                statement.setString(2, userId)
                statement.setObject(3, issuedAt.atUtc())
                statement.setObject(4, expiresAt.atUtc())
                statement.executeUpdate()
            }
        }
    }

    override suspend fun accountForToken(tokenFingerprint: ByteArray, now: Instant): StoredAccount? =
        query { connection ->
            // The expiry is in the WHERE clause rather than checked afterwards, so an expired
            // token is indistinguishable from an absent one no matter what the caller does next.
            connection.prepareStatement(
                """
                SELECT u.id, u.username, u.password_hash
                FROM auth_tokens t
                JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.expires_at > ?
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, tokenFingerprint)
                statement.setObject(2, now.atUtc())
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.toAccount() else null
                }
            }
        }

    override suspend fun revokeToken(tokenFingerprint: ByteArray) {
        query { connection ->
            connection.prepareStatement("DELETE FROM auth_tokens WHERE token_hash = ?").use { statement ->
                statement.setBytes(1, tokenFingerprint)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun purgeExpiredTokens(now: Instant): Int = query { connection ->
        connection.prepareStatement("DELETE FROM auth_tokens WHERE expires_at <= ?").use { statement ->
            statement.setObject(1, now.atUtc())
            statement.executeUpdate()
        }
    }

    override suspend fun listSpaces(userId: String): List<StoredSpaceHeader> = query { connection ->
        // octet_length rather than the payload itself: the whole point of this endpoint is to
        // describe spaces without moving megabytes of them.
        connection.prepareStatement(
            """
            SELECT remote_id, name, id_prefix, revision, updated_at, octet_length(payload)
            FROM spaces
            WHERE user_id = ?::uuid
            ORDER BY remote_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredSpaceHeader(
                                remoteId = rows.getString(1),
                                name = rows.getString(2),
                                idPrefix = rows.getString(3),
                                revision = rows.getLong(4),
                                updatedAt = rows.instantAt(5),
                                payloadBytes = rows.getLong(6),
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun loadSpace(userId: String, remoteId: String): StoredSpace? = query { connection ->
        connection.prepareStatement(
            """
            SELECT name, id_prefix, revision, updated_at, octet_length(payload), payload
            FROM spaces
            WHERE user_id = ?::uuid AND remote_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, remoteId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                StoredSpace(
                    header = StoredSpaceHeader(
                        remoteId = remoteId,
                        name = rows.getString(1),
                        idPrefix = rows.getString(2),
                        revision = rows.getLong(3),
                        updatedAt = rows.instantAt(4),
                        payloadBytes = rows.getLong(5),
                    ),
                    payload = rows.getString(6),
                )
            }
        }
    }

    override suspend fun spaceRevision(userId: String, remoteId: String): Long? = query { connection ->
        connection.readRevision(userId, remoteId)
    }

    override suspend fun createSpace(
        userId: String,
        remoteId: String,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult = query { connection ->
        val written = connection.prepareStatement(
            """
            INSERT INTO spaces (user_id, remote_id, name, id_prefix, revision, updated_at, payload)
            VALUES (?::uuid, ?, ?, ?, $FIRST_REVISION, ?, ?)
            ON CONFLICT (user_id, remote_id) DO NOTHING
            RETURNING revision, updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, remoteId)
            statement.setString(3, name)
            statement.setString(4, idPrefix)
            statement.setObject(5, now.atUtc())
            statement.setString(6, payload)
            statement.executeQuery().use { rows -> rows.toReceipt() }
        }
        if (written != null) {
            WriteResult.Written(written)
        } else {
            WriteResult.Conflict(connection.readRevision(userId, remoteId))
        }
    }

    override suspend fun updateSpace(
        userId: String,
        remoteId: String,
        expectedRevision: Long,
        name: String,
        idPrefix: String,
        payload: String,
        now: Instant,
    ): WriteResult = query { connection ->
        val written = connection.prepareStatement(
            """
            UPDATE spaces
            SET name = ?, id_prefix = ?, payload = ?, revision = revision + 1, updated_at = ?
            WHERE user_id = ?::uuid AND remote_id = ? AND revision = ?
            RETURNING revision, updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, idPrefix)
            statement.setString(3, payload)
            statement.setObject(4, now.atUtc())
            statement.setString(5, userId)
            statement.setString(6, remoteId)
            statement.setLong(7, expectedRevision)
            statement.executeQuery().use { rows -> rows.toReceipt() }
        }
        written?.let { WriteResult.Written(it) } ?: connection.explainMiss(userId, remoteId)
    }

    override suspend fun deleteSpace(
        userId: String,
        remoteId: String,
        expectedRevision: Long,
    ): WriteResult = query { connection ->
        val deleted = connection.prepareStatement(
            """
            DELETE FROM spaces
            WHERE user_id = ?::uuid AND remote_id = ? AND revision = ?
            RETURNING revision, updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, remoteId)
            statement.setLong(3, expectedRevision)
            statement.executeQuery().use { rows -> rows.toReceipt() }
        }
        deleted?.let { WriteResult.Written(it) } ?: connection.explainMiss(userId, remoteId)
    }

    override fun close() {
        if (closeDataSource) (dataSource as? AutoCloseable)?.close()
    }

    /**
     * Why a guarded write matched no row: the space is gone, or it has moved on.
     *
     * Read after the write rather than before it, so the fast path — the write succeeding — costs
     * one statement. The revision reported can already be stale by the time the caller sees it,
     * which is harmless: the retry carries its own guard.
     */
    private fun Connection.explainMiss(userId: String, remoteId: String): WriteResult {
        val current = readRevision(userId, remoteId)
        return if (current == null) WriteResult.NotFound else WriteResult.Conflict(current)
    }

    private fun Connection.readRevision(userId: String, remoteId: String): Long? =
        prepareStatement("SELECT revision FROM spaces WHERE user_id = ?::uuid AND remote_id = ?")
            .use { statement ->
                statement.setString(1, userId)
                statement.setString(2, remoteId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }

    private suspend fun <T> query(block: (Connection) -> T): T = withContext(io) {
        useConnection(block)
    }

    private fun <T> useConnection(block: (Connection) -> T): T =
        dataSource.connection.use { connection -> block(connection) }

    private companion object {
        const val FIRST_REVISION = 1L

        fun ResultSet.toAccount() = StoredAccount(
            userId = getString(1),
            username = getString(2),
            passwordHash = getString(3),
        )

        fun ResultSet.toReceipt(): WriteReceipt? =
            if (next()) WriteReceipt(getLong(1), instantAt(2)) else null

        /**
         * A `timestamptz` as an instant, read through `OffsetDateTime`.
         *
         * `getTimestamp` would reinterpret the value in the JVM's default zone, which turns the
         * same row into different instants on two machines.
         */
        fun ResultSet.instantAt(column: Int): Instant {
            val moment = getObject(column, OffsetDateTime::class.java).toInstant()
            return Instant.fromEpochSeconds(moment.epochSecond, moment.nano.toLong())
        }

        fun Instant.atUtc(): OffsetDateTime = OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()),
            ZoneOffset.UTC,
        )
    }
}

/** Builds the pool the server runs on, and the store over it. */
fun postgresSyncStore(config: StorageConfig.Postgres): PostgresSyncStore {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.username
        password = config.password
        maximumPoolSize = config.maxPoolSize
        // Every statement here is a single round trip, so nothing needs a transaction spanning
        // more than one; leaving autocommit on keeps a returned connection from holding a lock.
        isAutoCommit = true
        poolName = "zheduler-sync"
    }
    return PostgresSyncStore(HikariDataSource(hikari), closeDataSource = true, poolSize = config.maxPoolSize)
}

/**
 * The tables, created on first start.
 *
 * Plain `CREATE TABLE IF NOT EXISTS` rather than a migration tool, because there is exactly one
 * version of this schema so far. The moment a column has to change, this becomes a versioned list
 * and not a set of statements that happen to be idempotent.
 */
object PostgresSchema {

    fun apply(connection: Connection) {
        connection.createStatement().use { statement ->
            STATEMENTS.forEach { sql ->
                try {
                    statement.execute(sql)
                } catch (failure: SQLException) {
                    throw SQLException("Could not apply schema statement: $sql", failure)
                }
            }
        }
    }

    private val STATEMENTS = listOf(
        // Ids are generated by the server, not by the database, so this needs no uuid extension
        // and works on a PostgreSQL the deployer is not superuser on.
        """
        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS auth_tokens (
            token_hash BYTEA PRIMARY KEY,
            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            issued_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS auth_tokens_expires_at ON auth_tokens (expires_at)",
        // The composite primary key is the tenant boundary: `remote_id` alone is not a key, so
        // no query can address a row without saying whose it is.
        """
        CREATE TABLE IF NOT EXISTS spaces (
            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            remote_id TEXT NOT NULL,
            name TEXT NOT NULL,
            id_prefix TEXT NOT NULL,
            revision BIGINT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            payload TEXT NOT NULL,
            PRIMARY KEY (user_id, remote_id)
        )
        """.trimIndent(),
    )
}
