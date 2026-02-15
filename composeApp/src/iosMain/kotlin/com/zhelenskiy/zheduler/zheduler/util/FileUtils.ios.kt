package com.zhelenskiy.zheduler.zheduler.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.writeString

actual suspend fun PlatformFile.writeStringToFile(content: String) {
    writeString(content)
}
