package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * Version 1 was SQLDelight's, and the entities mirrored that schema exactly so Room could adopt
 * existing files untouched. Since then: version 2 drops three indexes that schema carried for
 * nothing, version 3 stores each task's estimate as a total in seconds, version 4 indexes it,
 * version 5 gives a task its own due-time sound, version 6 adds the address book of places. Any
 * further schema change
 * needs another version bump and a `Migration` added to `withZhedulerMigrations`, so every
 * platform's builder picks it up.
 */
@Database(
    entities = [
        Spaces::class,
        Tasks::class,
        TaskTags::class,
        TaskConnections::class,
        StatusChanges::class,
        SpaceNextIds::class,
        Tags::class,
        FilterStates::class,
        ViewModes::class,
        FilterPanelStates::class,
        CustomViewModes::class,
        ActiveViewModes::class,
        SavedFilters::class,
        SavedLocations::class,
    ],
    version = 6,
    exportSchema = true,
)
@ConstructedBy(ZhedulerDatabaseConstructor::class)
abstract class ZhedulerDatabase : RoomDatabase() {
    abstract fun dao(): ZhedulerDao
}

// The Room compiler generates the actual implementations.
@Suppress("KotlinNoActualForExpect")
expect object ZhedulerDatabaseConstructor : RoomDatabaseConstructor<ZhedulerDatabase> {
    override fun initialize(): ZhedulerDatabase
}

/** Name of the database file, unchanged from the SQLDelight setup. */
internal const val DATABASE_FILE_NAME = "zheduler.db"
