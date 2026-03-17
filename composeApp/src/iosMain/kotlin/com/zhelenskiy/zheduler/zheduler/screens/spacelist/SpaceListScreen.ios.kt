package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher as fileKitRememberFileSaverLauncher

actual val supportsDownloading: Boolean
    get() = false

actual suspend fun write(content: String, name: String) {
}

@Composable
internal actual fun rememberFileSaverLauncher(onResult: (PlatformFile?) -> Unit): SaverResultLauncher? =
    fileKitRememberFileSaverLauncher(onResult = onResult)
