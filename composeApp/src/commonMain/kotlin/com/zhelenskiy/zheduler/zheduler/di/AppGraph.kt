@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.di

import androidx.lifecycle.SavedStateHandle
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.db.ZhedulerDatabase
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.CalendarContainerFactory
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskDetailViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


/**
 * Factory interface for creating TaskListViewModel instances with runtime parameters.
 */
fun interface TaskListViewModelFactory {
    fun create(spaceId: String): TaskListViewModel
}

/**
 * Factory interface for creating NewTaskViewModel instances with runtime parameters.
 */
fun interface NewTaskViewModelFactory {
    fun create(spaceId: String, prefilledConnection: TaskConnection?, taskIdToCopy: String?): NewTaskViewModel
}

/**
 * Factory interface for creating TaskDetailViewModel instances with runtime parameters.
 */
fun interface TaskDetailViewModelFactory {
    fun create(
        spaceId: String,
        taskId: String
    ): TaskDetailViewModel
}

/**
 * Factory interface for creating TaskEditViewModel instances with runtime parameters.
 */
fun interface TaskEditViewModelFactory {
    fun create(
        spaceId: String,
        taskId: String,
        savedStateHandle: SavedStateHandle
    ): TaskEditViewModel
}

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
     * Provides the singleton SqlDelightTaskRepository instance.
     */
    val taskRepository: SqlDelightTaskRepository

    /**
     * Singleton SpaceListContainer - preserves search state across navigation.
     */
    val spaceListContainer: SpaceListContainer

    /**
     * Factory for creating TaskListViewModel instances with runtime parameters.
     */
    val taskListViewModelFactory: TaskListViewModelFactory

    /**
     * Factory for creating CalendarContainer instances with runtime parameters.
     */
    val calendarContainerFactory: CalendarContainerFactory

    /**
     * Factory for creating NewTaskViewModel instances with runtime parameters.
     */
    val newTaskViewModelFactory: NewTaskViewModelFactory

    /**
     * Factory for creating TaskDetailViewModel instances with runtime parameters.
     */
    val taskDetailViewModelFactory: TaskDetailViewModelFactory

    /**
     * Factory for creating TaskEditViewModel instances with runtime parameters.
     */
    val taskEditViewModelFactory: TaskEditViewModelFactory

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
        fun provideTaskRepository(database: ZhedulerDatabase, clock: Clock): SqlDelightTaskRepository =
            SqlDelightTaskRepository(database, clock)

        @Provides
        @SingleIn(AppScope::class)
        fun provideSpaceListContainer(repository: SqlDelightTaskRepository): SpaceListContainer =
            SpaceListContainer(repository)

        @Provides
        fun provideTaskListViewModelFactory(repository: SqlDelightTaskRepository): TaskListViewModelFactory =
            TaskListViewModelFactory { spaceId ->
                TaskListViewModel(repository, spaceId)
            }

        @Provides
        fun provideCalendarContainerFactory(repository: SqlDelightTaskRepository): CalendarContainerFactory =
            CalendarContainerFactory { spaceId ->
                CalendarContainer(repository, spaceId)
            }

        @Provides
        fun provideNewTaskViewModelFactory(repository: SqlDelightTaskRepository): NewTaskViewModelFactory =
            NewTaskViewModelFactory { spaceId, prefilledConnection, taskIdToCopy ->
                NewTaskViewModel(repository, spaceId, prefilledConnection, taskIdToCopy)
            }

        @Provides
        fun provideTaskDetailViewModelFactory(repository: SqlDelightTaskRepository): TaskDetailViewModelFactory =
            TaskDetailViewModelFactory { spaceId, taskId ->
                TaskDetailViewModel(repository, spaceId, taskId)
            }

        @Provides
        fun provideTaskEditViewModelFactory(repository: SqlDelightTaskRepository): TaskEditViewModelFactory =
            TaskEditViewModelFactory { spaceId, taskId, savedStateHandle ->
                TaskEditViewModel(repository, spaceId, taskId, savedStateHandle)
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
