package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
    entities = [
        SpaceEntity::class,
        TaskEntity::class,
        TaskTagEntity::class,
        TaskConnectionEntity::class,
        StatusChangeEntity::class,
        SpaceNextIdEntity::class,
        TagEntity::class,
        FilterStateEntity::class,
        ViewModeEntity::class,
        FilterPanelStateEntity::class,
        CustomViewModeEntity::class,
        ActiveViewModeEntity::class,
        SavedFilterEntity::class
    ],
    version = 1
)
@ConstructedBy(ZhedulerRoomDatabaseConstructor::class)
abstract class ZhedulerRoomDatabase : RoomDatabase() {
    abstract fun zhedulerDao(): ZhedulerDao
}

@Suppress("KotlinNoActualForExpect")
expect object ZhedulerRoomDatabaseConstructor : RoomDatabaseConstructor<ZhedulerRoomDatabase> {
    override fun initialize(): ZhedulerRoomDatabase
}
