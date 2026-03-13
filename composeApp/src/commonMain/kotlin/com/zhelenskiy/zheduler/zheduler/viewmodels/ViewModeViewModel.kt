package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ViewModeViewModel(
    private val repository: TaskRepository,
    private val spaceId: String
) : ViewModel() {

    private val _viewModes = MutableStateFlow<List<ViewMode>>(emptyList())
    val viewModes: StateFlow<List<ViewMode>> = _viewModes.asStateFlow()

    private val _activeViewMode = MutableStateFlow<ViewMode?>(null)
    val activeViewMode: StateFlow<ViewMode?> = _activeViewMode.asStateFlow()

    private val _allTags = MutableStateFlow<Set<String>>(emptySet())
    val allTags: StateFlow<Set<String>> = _allTags.asStateFlow()

    init {
        loadViewModes()
        loadTags()
    }

    fun loadViewModes() {
        viewModelScope.launch {
            _viewModes.value = repository.getAllViewModes(spaceId)
            _activeViewMode.value = repository.getActiveViewMode(spaceId)
        }
    }

    private fun loadTags() {
        viewModelScope.launch {
            _allTags.value = repository.getAllTags()
        }
    }

    fun setActiveViewMode(viewModeId: String) {
        viewModelScope.launch {
            repository.setActiveViewMode(spaceId, viewModeId)
            _activeViewMode.value = repository.getActiveViewMode(spaceId)
        }
    }

    fun saveViewMode(viewMode: ViewMode) {
        viewModelScope.launch {
            repository.saveViewMode(viewMode)
            loadViewModes()
        }
    }

    fun copyViewMode(viewMode: ViewMode): ViewMode {
        return viewMode.copy(
            id = generateId(),
            name = "${viewMode.name} (Copy)",
            isBuiltIn = false
        )
    }

    fun deleteViewMode(viewModeId: String) {
        viewModelScope.launch {
            repository.deleteViewMode(spaceId, viewModeId)
            loadViewModes()
        }
    }

    suspend fun getViewModeById(viewModeId: String): ViewMode? {
        return repository.getViewModeById(spaceId, viewModeId)
    }
}
