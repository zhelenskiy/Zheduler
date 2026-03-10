package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.util.writeStringToFile
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal actual fun getFileSaverLauncher(
    coroutineScope: CoroutineScope,
    space: Space,
    prettyPrint: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    exportSpaceToJson: suspend (spaceId: String, prettyPrint: Boolean) -> String?
): SaverResultLauncher? = rememberFileSaverLauncher { file ->
    file?.let {
        coroutineScope.launch {
            try {
                val jsonData = exportSpaceToJson(space.id, prettyPrint)
                if (jsonData != null) {
                    it.writeStringToFile(jsonData)
                    onDismiss()
                    snackbarHostState.showSnackbar("Space exported to ${it.name}")
                } else {
                    snackbarHostState.showSnackbar("Failed to export space")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error saving file: ${e.message}")
            }
        }
    }
}

actual val supportsDownloading: Boolean
    get() = false
actual suspend fun write(content: String, name: String) {
}