@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android-specific database test classes that use Robolectric.
 *
 * These classes extend the abstract test classes from commonTest and implement
 * DatabaseRepositoryTest to get both the test logic and database support.
 *
 * The @RunWith and @Config annotations provide the Robolectric environment
 * needed for ApplicationProvider.getApplicationContext() to work.
 */

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseTaskRepositoryTest : TaskRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseTaskAdvancedRepositoryTest : TaskAdvancedRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseTaskAutomationRepositoryTest : TaskAutomationRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseTaskFiltersRepositoryTest : TaskFiltersRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseRecurrenceRepositoryTest : RecurrenceRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseRecurrenceEdgeCasesRepositoryTest : RecurrenceEdgeCasesRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseConcurrencyRepositoryTest : ConcurrencyRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseCalculateStatusFromSubtasksRepositoryTest : CalculateStatusFromSubtasksRepositoryTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseIsMissedRepositoryTest : IsMissedRepositoryTest(), DatabaseRepositoryTest
