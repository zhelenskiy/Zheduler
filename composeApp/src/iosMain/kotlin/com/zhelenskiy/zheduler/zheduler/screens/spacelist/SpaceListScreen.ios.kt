package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.util.writeStringToFile
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListViewModel
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal actual fun getFileSaverLauncher(
    coroutineScope: CoroutineScope,
    viewModel: SpaceListViewModel,
    space: Space,
    prettyPrint: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
): SaverResultLauncher? = rememberFileSaverLauncher { file ->
    file?.let {
        coroutineScope.launch {
            try {
                val jsonData = viewModel.exportSpaceToJson(space.id, prettyPrint)
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