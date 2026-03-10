package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.zhelenskiy.zheduler.zheduler.Space
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.download
import kotlinx.coroutines.CoroutineScope

@Composable
internal actual fun getFileSaverLauncher(
    coroutineScope: CoroutineScope,
    space: Space,
    prettyPrint: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    exportSpaceToJson: suspend (spaceId: String, prettyPrint: Boolean) -> String?
): SaverResultLauncher? = null


actual val supportsDownloading = true
actual suspend fun write(content: String, name: String) {
    FileKit.download(content.encodeToByteArray(), name)
}
