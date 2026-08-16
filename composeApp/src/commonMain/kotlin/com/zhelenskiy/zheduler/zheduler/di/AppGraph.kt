@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import com.zhelenskiy.zheduler.zheduler.db.RoomTaskRepository
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarContainerFactory
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskContainerFactory
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskDetailContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskDetailContainerFactory
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditContainerFactory
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListContainerFactory
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Main dependency graph for the application.
 * Metro generates the implementation at compile time.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {

    /**
     * Provides the singleton Clock instance.
     */
    val clock: Clock

    /**
     * Provides the singleton ZhedulerDatabase instance.
     */
    val database: ZhedulerDatabase

    /**
     * Provides the singleton RoomTaskRepository instance.
     */
    val taskRepository: RoomTaskRepository

    /**
     * Singleton SpaceListContainer - preserves search state across navigation.
     */
    val spaceListContainer: SpaceListContainer

    /**
     * Factory for creating TaskListContainer instances with runtime parameters.
     */
    val taskListContainerFactory: TaskListContainerFactory

    /**
     * Factory for creating CalendarContainer instances with runtime parameters.
     */
    val calendarContainerFactory: CalendarContainerFactory

    /**
     * Factory for creating NewTaskContainer instances with runtime parameters.
     */
    val newTaskContainerFactory: NewTaskContainerFactory

    /**
     * Factory for creating TaskDetailContainer instances with runtime parameters.
     */
    val taskDetailContainerFactory: TaskDetailContainerFactory

    /**
     * Factory for creating TaskEditContainer instances with runtime parameters.
     */
    val taskEditContainerFactory: TaskEditContainerFactory

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideClock(): Clock = Clock.System

        @Provides
        @SingleIn(AppScope::class)
        fun provideDeferredDatabaseForDI(): Deferred<ZhedulerDatabase> = deferredDatabaseInstance

        @OptIn(ExperimentalCoroutinesApi::class)
        @Provides
        @SingleIn(AppScope::class)
        fun provideDatabaseForDI(deferredDatabase: Deferred<ZhedulerDatabase>): ZhedulerDatabase =
            deferredDatabase.getCompleted()

        @Provides
        @SingleIn(AppScope::class)
        fun provideTaskRepository(database: ZhedulerDatabase, clock: Clock): RoomTaskRepository =
            RoomTaskRepository(database, clock)

        @Provides
        @SingleIn(AppScope::class)
        fun provideSpaceListContainer(repository: RoomTaskRepository): SpaceListContainer =
            SpaceListContainer(repository)

        @Provides
        fun provideTaskListContainerFactory(repository: RoomTaskRepository): TaskListContainerFactory =
            TaskListContainerFactory { spaceId ->
                TaskListContainer(repository, spaceId)
            }

        @Provides
        fun provideCalendarContainerFactory(repository: RoomTaskRepository): CalendarContainerFactory =
            CalendarContainerFactory { spaceId ->
                CalendarContainer(repository, spaceId)
            }

        @Provides
        fun provideNewTaskContainerFactory(repository: RoomTaskRepository): NewTaskContainerFactory =
            NewTaskContainerFactory { spaceId, prefilledConnection, taskIdToCopy, savedStateHandle ->
                NewTaskContainer(repository, spaceId, prefilledConnection, taskIdToCopy, savedStateHandle)
            }

        @Provides
        fun provideTaskDetailContainerFactory(repository: RoomTaskRepository): TaskDetailContainerFactory =
            TaskDetailContainerFactory { spaceId, taskId ->
                TaskDetailContainer(repository, spaceId, taskId)
            }

        @Provides
        fun provideTaskEditContainerFactory(repository: RoomTaskRepository): TaskEditContainerFactory =
            TaskEditContainerFactory { spaceId, taskId, savedStateHandle ->
                TaskEditContainer(repository, spaceId, taskId, savedStateHandle)
            }
    }
}

/**
 * Expect function to provide platform-specific deferred database
 */
expect fun provideDeferredDatabase(): Deferred<ZhedulerDatabase>

/**
 * Cached deferred database instance to ensure we use the same one everywhere.
 */
val deferredDatabaseInstance: Deferred<ZhedulerDatabase> by lazy { provideDeferredDatabase() }
