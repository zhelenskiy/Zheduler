package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.paging.PagingDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * A paged list whose query changes over time — a search field, a filter, a selection dialog.
 *
 * Every distinct query gets its own [Pager]; the pages are cached in the container's scope so
 * collapsing and reopening a dialog does not reload from the first page. Repository mutations
 * invalidate the current source instead of rebuilding it, so the list refreshes in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedQuery<Q : Any, T : Any>(
    scope: CoroutineScope,
    initialQuery: Q,
    changes: Flow<Unit>,
    config: PagingConfig = PagingDefaults.dialogList,
    private val pagingSource: (Q) -> PagingSource<Int, T>,
) {
    private val query = MutableStateFlow(initialQuery)
    private var currentFactory: InvalidatingPagingSourceFactory<Int, T>? = null

    val pages: Flow<PagingData<T>> = query
        .flatMapLatest { current ->
            val factory = InvalidatingPagingSourceFactory { pagingSource(current) }
            currentFactory = factory
            // Called through a lambda rather than passed directly: the factory type is only a
            // function type on JVM, and this module also compiles for js/wasmJs/native.
            Pager(config, pagingSourceFactory = { factory() }).flow
        }
        .cachedIn(scope)

    init {
        scope.launch {
            changes.collect { currentFactory?.invalidate() }
        }
    }

    /** Switch to a new query; the previous pager is dropped. */
    fun setQuery(newQuery: Q) {
        query.value = newQuery
    }
}

/** What the tag-selection dialog is currently asking for. */
data class TagQuery(
    val searchQuery: String = "",
    val excludeTags: Set<String> = emptySet(),
)

/** What the connection dialog is currently asking for, including the data the cycle check needs. */
data class ConnectionQuery(
    val searchQuery: String = "",
    val excludeTaskIds: Set<String> = emptySet(),
    val connectionType: ConnectionType = ConnectionType.RelatesTo,
    val existingConnections: Set<TaskConnection> = emptySet(),
)
