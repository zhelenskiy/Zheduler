package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val loadedTasks: Map<String, Task> = emptyMap()
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
) : Container<CalendarState, CalendarIntent, CalendarAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(CalendarState(), scope) {
        configure {
            name = "CalendarStore"
        }

        reduce { intent ->
            when (intent) {
                is CalendarIntent.LoadStatusChanges -> loadStatusChanges(intent.year, intent.month)
                is CalendarIntent.LoadTask -> loadTask(intent.taskId)
            }
        }
    }

    private suspend fun CalendarPipelineContext.loadTask(taskId: String) {
        val task = repository.getTaskById(taskId) ?: return
        updateState {
            if (taskId in loadedTasks) this else copy(loadedTasks = loadedTasks + (taskId to task))
        }
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
