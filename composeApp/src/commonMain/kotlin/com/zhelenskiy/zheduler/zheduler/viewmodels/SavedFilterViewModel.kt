package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.SavedFilterWithViewMode
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SavedFilterViewModel(
    private val repository: TaskRepository,
    val spaceId: String
) : ViewModel() {

    private val _savedFilters = MutableStateFlow<List<SavedFilterWithViewMode>>(emptyList())
    val savedFilters: StateFlow<List<SavedFilterWithViewMode>> = _savedFilters.asStateFlow()

    private val _viewModes = MutableStateFlow<List<ViewMode>>(emptyList())
    val viewModes: StateFlow<List<ViewMode>> = _viewModes.asStateFlow()

    private val _allTags = MutableStateFlow<Set<String>>(emptySet())
    val allTags: StateFlow<Set<String>> = _allTags.asStateFlow()

    private val _spaceIdPrefix = MutableStateFlow<String?>(null)
    val spaceIdPrefix: StateFlow<String?> = _spaceIdPrefix.asStateFlow()

    init {
        loadSavedFilters()
        loadViewModes()
        loadAllTags()
        loadSpaceIdPrefix()
    }

    fun loadSavedFilters() {
        viewModelScope.launch {
            _savedFilters.value = repository.getAllSavedFiltersWithViewModes(spaceId)
        }
    }

    private fun loadViewModes() {
        viewModelScope.launch {
            _viewModes.value = repository.getAllViewModes(spaceId)
        }
    }

    private fun loadAllTags() {
        viewModelScope.launch {
            _allTags.value = repository.getAllTags(spaceId)
        }
    }

    private fun loadSpaceIdPrefix() {
        viewModelScope.launch {
            val space = repository.getSpaceById(spaceId)
            _spaceIdPrefix.value = space?.idPrefix
        }
    }

    fun saveSavedFilter(filter: SavedFilter) {
        viewModelScope.launch {
            repository.saveSavedFilter(filter)
            loadSavedFilters()
        }
    }

    fun generateId(): String = generateIdImpl()

    fun deleteFilter(filterId: String) {
        viewModelScope.launch {
            repository.deleteSavedFilter(spaceId, filterId)
            loadSavedFilters()
        }
    }
}
