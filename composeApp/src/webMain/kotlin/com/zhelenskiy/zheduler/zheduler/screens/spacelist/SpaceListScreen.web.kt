package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.download

actual val supportsDownloading = true

actual suspend fun write(content: String, name: String) {
    FileKit.download(content.encodeToByteArray(), name)
}

@Composable
internal actual fun rememberFileSaverLauncher(onResult: (PlatformFile?) -> Unit): SaverResultLauncher? = null
