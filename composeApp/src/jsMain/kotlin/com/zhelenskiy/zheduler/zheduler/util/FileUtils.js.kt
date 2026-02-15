package com.zhelenskiy.zheduler.zheduler.util

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.download
import io.github.vinceglb.filekit.name

actual suspend fun PlatformFile.writeStringToFile(content: String) {
    FileKit.download(content.encodeToByteArray(), name)
}
