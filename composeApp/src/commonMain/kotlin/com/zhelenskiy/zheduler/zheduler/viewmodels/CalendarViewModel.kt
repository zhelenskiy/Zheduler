package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.StatusChangeEvent
import kotlinx.datetime.LocalDate

/**
 * ViewModel for the calendar screen that displays task status changes by date
 */
class CalendarViewModel(
    private val repository: SqlDelightTaskRepository,
    private val spaceId: String
) {
    /**
     * Get task by ID
     */
    suspend fun getTaskById(taskId: String): Task? {
        return repository.getById(taskId)
    }

    /**
     * Get status changes grouped by date for a specific month
     * Delegates to repository
     *
     * @param year The year to filter by
     * @param month The month to filter by (1-12)
     */
    suspend fun getStatusChangesByDate(year: Int, month: Int): Map<LocalDate, List<StatusChangeEvent>> {
        return repository.getStatusChangesByDate(spaceId, year, month)
    }
}
