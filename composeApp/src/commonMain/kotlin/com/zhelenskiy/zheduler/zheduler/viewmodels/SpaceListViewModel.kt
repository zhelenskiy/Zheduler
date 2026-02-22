package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Search options for space list
enum class SpaceSearchOption {
    Name, Prefix;

    val displayName: String get() = when (this) {
        Name -> "Name"
        Prefix -> "ID Prefix"
    }
}

class SpaceListViewModel(
    private val repository: TaskRepository
) : ViewModel() {

    private val spaceUpdates = MutableStateFlow(Any())

    private val _hasSpaces = MutableStateFlow<Boolean?>(null)
    val hasSpaces: StateFlow<Boolean?> = _hasSpaces.asStateFlow()

    private val _allTags = MutableStateFlow<Set<String>>(emptySet())
    val allTags: StateFlow<Set<String>> = _allTags.asStateFlow()

    // Search state managed by ViewModel
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchOptions = MutableStateFlow(setOf(SpaceSearchOption.Name, SpaceSearchOption.Prefix))
    val searchOptions: StateFlow<Set<SpaceSearchOption>> = _searchOptions.asStateFlow()

    private val _showSearchOptions = MutableStateFlow(false)
    val showSearchOptions: StateFlow<Boolean> = _showSearchOptions.asStateFlow()

    // Filtered spaces computed from search criteria using repository method
    val filteredSpaces: StateFlow<List<Space>?> = combine(
        spaceUpdates,
        _searchQuery,
        _searchOptions
    ) { _, query, options ->
        repository.filterSpaces(
            query = query,
            searchInName = SpaceSearchOption.Name in options,
            searchInPrefix = SpaceSearchOption.Prefix in options
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        loadSpaces()
    }

    fun loadSpaces() {
        viewModelScope.launch {
            _hasSpaces.value = repository.hasSpaces()
            _allTags.value = repository.getAllTags()
            spaceUpdates.emit(Any())
        }
    }

    fun loadTags() {
        viewModelScope.launch {
            _allTags.value = repository.getAllTags()
        }
    }

    suspend fun addTag(tag: String): Boolean {
        val result = repository.addTag(tag)
        if (result) {
            loadTags()
        }
        return result
    }

    suspend fun deleteTag(tag: String): Boolean {
        val result = repository.deleteTag(tag)
        if (result) {
            loadTags()
        }
        return result
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllData()
            loadSpaces()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
    }

    fun toggleSearchOption(option: SpaceSearchOption) {
        val current = _searchOptions.value
        _searchOptions.value = if (option in current && current.size > 1) {
            current - option
        } else {
            current + option
        }
    }

    fun toggleShowSearchOptions() {
        _showSearchOptions.value = !_showSearchOptions.value
    }

    suspend fun addSpace(name: String, idPrefix: String): Space? {
        val space = repository.createSpace(name, idPrefix)
        if (space != null) {
            loadSpaces()
        }
        return space
    }

    suspend fun updateSpace(oldPrefix: String, newName: String): Boolean {
        val result = repository.updateSpaceName(oldPrefix, newName)
        if (result) {
            loadSpaces()
        }
        return result
    }

    suspend fun deleteSpace(prefix: String): Boolean {
        val result = repository.deleteSpace(prefix)
        if (result) {
            loadSpaces()
        }
        return result
    }

    /**
     * Export a space to JSON string
     * @param spaceId The ID of the space to export
     * @param prettyPrint Whether to format JSON with indentation (default: false)
     * @return JSON string containing the space data, or null if space not found
     */
    suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean = false): String? {
        return repository.exportSpaceToJson(spaceId, prettyPrint)
    }

    /**
     * Import a space from JSON string
     * @param jsonString The JSON string containing the space data
     * @return The imported space, or null if import failed
     */
    suspend fun importSpaceFromJson(jsonString: String): Space? {
        val space = repository.importSpaceFromJson(jsonString)
        if (space != null) {
            loadSpaces()
        }
        return space
    }
}
