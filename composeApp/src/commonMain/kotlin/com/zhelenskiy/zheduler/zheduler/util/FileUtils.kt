package com.zhelenskiy.zheduler.zheduler.util

import io.github.vinceglb.filekit.PlatformFile

// Write, read, and name need platform-specific implementations
expect suspend fun PlatformFile.writeStringToFile(content: String)
