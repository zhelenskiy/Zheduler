package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.datetime.LocalDate

/**
 * ViewModel for the calendar screen that displays task status changes by date
 */
class CalendarViewModel(
    private val repository: TaskRepository,
    private val spaceId: String
) {
    /**
     * Get task by ID
     */
    suspend fun getTaskById(taskId: String): Task? = repository.getTaskById(taskId)

    /**
     * Get status changes grouped by date for a specific month
     * Delegates to repository
     *
     * @param year The year to filter by
     * @param month The month to filter by (1-12)
     */
    suspend fun getStatusChangesByDate(year: Int, month: Int): Map<LocalDate, List<StatusChangeEvent>> =
        repository.getStatusChangesByDate(spaceId, year, month)
}
