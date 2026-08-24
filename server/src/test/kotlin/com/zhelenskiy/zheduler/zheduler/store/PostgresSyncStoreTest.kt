package com.zhelenskiy.zheduler.zheduler.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * The same contract, against a real PostgreSQL in a container.
 *
 * This is the suite that can catch what the in-memory one cannot: whether the composite primary
 * key really is the tenant boundary, whether `UPDATE ... WHERE revision = ?` really is atomic
 * across connections, and whether a multi-megabyte payload survives being TOASTed and read back.
 *
 * One container for the whole class, and an empty schema per test — starting PostgreSQL costs
 * seconds, dropping a schema costs milliseconds.
 */
class PostgresSyncStoreTest : SyncStoreContractTest() {

    override fun createStore(): SyncStore {
        val source = requireNotNull(dataSource)
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
        // The store's own initialiser puts the tables back, which is also how a first start works.
        return PostgresSyncStore(source, closeDataSource = false, poolSize = POOL_SIZE)
    }

    companion object {
        private const val POOL_SIZE = 8

        private var container: PostgreSQLContainer<*>? = null
        private var dataSource: HikariDataSource? = null

        @JvmStatic
        @BeforeClass
        fun startDatabase() {
            // Skipped rather than failed when there is no Docker: this repository has no CI, and a
            // suite that cannot run on the developer's machine would simply be deleted.
            Assume.assumeTrue(
                "Docker is not available; skipping the PostgreSQL suite.",
                DockerClientFactory.instance().isDockerAvailable,
            )
            val started = PostgreSQLContainer("postgres:16-alpine").apply { start() }
            container = started
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = started.jdbcUrl
                    username = started.username
                    password = started.password
                    maximumPoolSize = POOL_SIZE
                    isAutoCommit = true
                }
            )
        }

        @JvmStatic
        @AfterClass
        fun stopDatabase() {
            dataSource?.close()
            container?.stop()
            dataSource = null
            container = null
        }
    }
}
