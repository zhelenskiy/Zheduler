@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlin.time.Clock

/**
 * Result of creating a repository with a test space.
 */
data class RepositoryWithSpace(
    val repository: TaskRepository,
    val spaceId: String
)

interface AbstractRepositoryTest {
    suspend fun createEmptyRepository(clock: Clock = Clock.System): TaskRepository

    /**
     * Creates a TaskRepository with a test space already set up.
     * The space is named "Test" with prefix "TEST".
     * @param clock The clock to use for the repository. Defaults to Clock.System.
     * @return A pair of the repository and the space ID.
     */
    suspend fun createRepositoryWithSpace(clock: Clock = Clock.System): RepositoryWithSpace {
        val repo = createEmptyRepository(clock)
        val space = repo.createSpace("Test", "TEST")!!
        return RepositoryWithSpace(repo, space.id)
    }
}

interface InMemoryRepositoryTest : AbstractRepositoryTest {
    override suspend fun createEmptyRepository(clock: Clock): TaskRepository = InMemoryTaskRepository(clock)
}

/**
 * Factory function to create a database-backed repository.
 * Platform-specific implementations provide the appropriate SQLite driver.
 */
expect suspend fun createDatabaseRepository(clock: Clock = Clock.System): TaskRepository

/**
 * Optional cleanup function called after each database test.
 * Platform-specific implementations can provide cleanup logic (e.g., closing drivers).
 */
expect fun cleanupDatabaseTest()

/**
 * Interface for database repository tests.
 * Uses the platform-specific createDatabaseRepository() factory.
 */
interface DatabaseRepositoryTest : AbstractRepositoryTest {
    override suspend fun createEmptyRepository(clock: Clock): TaskRepository = createDatabaseRepository(clock)

    @kotlin.test.AfterTest
    fun afterEachTest()  {
        cleanupDatabaseTest()
    }
}
