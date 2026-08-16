package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems

/**
 * Status rows for a paged list: what the first page did, then what the next one is doing.
 *
 * A first page that fails is the one that has to be said out loud. Paging catches the exception
 * and reports it as a load state, so it never reaches the store's recover plugin — and with only
 * the append states handled, the screen drew nothing at all: no list, no message, no way to try
 * again, indistinguishable from a space with nothing in it.
 */
fun <T : Any> LazyListScope.pagingLoadStatus(items: LazyPagingItems<T>, keyPrefix: String = "") {
    if (items.loadState.refresh is LoadState.Error) {
        item(key = "${keyPrefix}paging_refresh_error") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Could not load",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { items.retry() }) { Text("Retry") }
            }
        }
    }

    when (items.loadState.append) {
        is LoadState.Loading -> item(key = "${keyPrefix}paging_append_loading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        is LoadState.Error -> item(key = "${keyPrefix}paging_append_error") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Could not load more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { items.retry() }) { Text("Retry") }
            }
        }

        else -> Unit
    }
}

/** True once the first page has settled and there is nothing to show. */
val LazyPagingItems<*>.isEmptyAfterRefresh: Boolean
    get() = loadState.refresh is LoadState.NotLoading && itemCount == 0
