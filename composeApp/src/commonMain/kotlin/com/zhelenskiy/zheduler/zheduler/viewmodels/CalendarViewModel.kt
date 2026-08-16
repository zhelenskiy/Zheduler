package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.LocalDate
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce

data class CalendarState(
    val currentYear: Int = 0,
    val currentMonth: Int = 0,
    val statusChangesByDate: Map<LocalDate, List<StatusChangeEvent>> = emptyMap(),
    val loadedTasks: PersistentMap<String, Task> = persistentMapOf()
) : MVIState

sealed interface CalendarIntent : MVIIntent {
    data class LoadStatusChanges(val year: Int, val month: Int) : CalendarIntent
    data class LoadTask(val taskId: String) : CalendarIntent
}

sealed interface CalendarAction : MVIAction

private typealias CalendarPipelineContext = PipelineContext<CalendarState, CalendarIntent, CalendarAction>

/**
 * Container for the calendar screen that displays task status changes by date
 */
class CalendarContainer(
    private val repository: TaskRepository,
    private val spaceId: String
) : ScopedContainer(), Container<CalendarState, CalendarIntent, CalendarAction> {

    override val store = store(CalendarState(), scope) {
        reportingFailuresAs("CalendarStore")

        reduce { intent ->
            when (intent) {
                is CalendarIntent.LoadStatusChanges -> loadStatusChanges(intent.year, intent.month)
                is CalendarIntent.LoadTask -> loadTask(intent.taskId)
            }
        }
    }

    private suspend fun CalendarPipelineContext.loadTask(taskId: String) {
        val task = repository.getTaskById(taskId) ?: return
        // Always the freshly read task. Keeping the cached one meant a blocker or referenced task
        // edited from this screen still showed its old title and status on return, for as long as
        // the screen stayed open.
        updateState { copy(loadedTasks = loadedTasks.putting(taskId, task)) }
    }

    private suspend fun CalendarPipelineContext.loadStatusChanges(
        year: Int,
        month: Int
    ) {
        val statusChanges = repository.getStatusChangesByDate(spaceId, year, month)
        updateState {
            copy(
                currentYear = year,
                currentMonth = month,
                statusChangesByDate = statusChanges
            )
        }
    }
}

/**
 * Factory interface for creating CalendarContainer instances with runtime parameters.
 */
fun interface CalendarContainerFactory {
    fun create(spaceId: String): CalendarContainer
}
