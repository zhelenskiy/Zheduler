package com.zhelenskiy.zheduler.zheduler.util

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.download
import io.github.vinceglb.filekit.name

/**
 * A browser cannot write to a path, so the nearest thing is handing the file to the user.
 *
 * Shared by both web targets. They had one implementation each, and they disagreed: Kotlin/JS
 * downloaded the file while Kotlin/Wasm threw. Neither is reached today — the web build has no
 * file saver, so the Save button is not shown — but two answers to the same question is one too
 * many for whenever it is.
 */
actual suspend fun PlatformFile.writeStringToFile(content: String) {
    FileKit.download(content.encodeToByteArray(), name)
}
