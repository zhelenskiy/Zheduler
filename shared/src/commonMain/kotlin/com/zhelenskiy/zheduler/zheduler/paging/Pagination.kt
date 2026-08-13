package com.zhelenskiy.zheduler.zheduler.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.coroutines.cancellation.CancellationException

/**
 * A window into a larger result set.
 *
 * [totalCount] is `null` when a repository cannot produce the total without doing the very work
 * pagination is meant to avoid (the connection search, for instance, filters candidates through a
 * cycle check one page at a time). In that case [hasMore] is the only signal of a next page.
 */
data class Page<out T>(
    val items: List<T>,
    val offset: Int,
    val totalCount: Int? = null,
    val hasMore: Boolean = totalCount != null && offset + items.size < totalCount,
)

/**
 * Slice an already-materialised list into a [Page]. Used by repositories whose results are computed
 * in memory anyway, and by the non-paged compatibility overloads.
 */
fun <T> List<T>.toPage(offset: Int, limit: Int): Page<T> {
    val from = offset.coerceIn(0, size)
    // offset + limit overflows Int for the "everything" window (limit = Int.MAX_VALUE).
    val to = (from.toLong() + limit.coerceAtLeast(0)).coerceAtMost(size.toLong()).toInt()
    return Page(items = subList(from, to).toList(), offset = from, totalCount = size)
}

/**
 * A [PagingSource] over any `(offset, limit) -> Page` loader.
 *
 * Keys are absolute offsets, which suits the repository APIs: they all take an offset/limit window.
 * Placeholders stay off (see [PagingDefaults]) so pages are appended as they load and the UI never
 * has to render a null item.
 */
class OffsetPagingSource<T : Any>(
    private val loadPage: suspend (offset: Int, limit: Int) -> Page<T>,
) : PagingSource<Int, T>() {

    override val jumpingSupported: Boolean get() = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val offset = (params.key ?: 0).coerceAtLeast(0)
        val page = loadPage(offset, params.loadSize)
        LoadResult.Page(
            data = page.items,
            prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
            nextKey = if (page.hasMore && page.items.isNotEmpty()) offset + page.items.size else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(state.config.pageSize)
            ?: anchorPage.nextKey?.minus(state.config.pageSize)
            ?: 0
    }
}

/** Page sizes for the app's paged lists, kept in one place so they can be tuned together. */
object PagingDefaults {
    /** The main task list: cards are tall, so a page is a few screens' worth. */
    val taskList = PagingConfig(
        pageSize = 30,
        prefetchDistance = 10,
        initialLoadSize = 60,
        enablePlaceholders = false,
    )

    /** Search/selection lists inside dialogs, which are capped at a few hundred dp of height. */
    val dialogList = PagingConfig(
        pageSize = 25,
        prefetchDistance = 10,
        initialLoadSize = 50,
        enablePlaceholders = false,
    )
}
