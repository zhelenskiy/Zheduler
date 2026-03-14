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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseSearchTasksForConnectionTest : SearchTasksForConnectionTest(), DatabaseRepositoryTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidDatabaseSavedFilterRepositoryTest : SavedFilterRepositoryTest(), DatabaseRepositoryTest

// Note: GroupedTaskQueriesComparisonTest is excluded from Android unit tests because the SQL queries
// for getTaskGroups/getTasksForGroup use json_extract which is not supported by Robolectric's SQLite.
// The requery sqlite-android library provides JSON1 support but uses native code that can't be loaded
// in the Robolectric JVM environment. The test runs on JVM which has full SQLite JSON1 extension support.
