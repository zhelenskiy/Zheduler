package com.zhelenskiy.zheduler.zheduler.util

import io.github.vinceglb.filekit.PlatformFile

actual suspend fun PlatformFile.writeStringToFile(content: String) {
    // Writing to files is not supported on WASM
    // Use FileKit.download() for exporting files on web platforms
    throw UnsupportedOperationException("Writing to files is not supported on WASM. Use FileKit.download() instead.")
}
