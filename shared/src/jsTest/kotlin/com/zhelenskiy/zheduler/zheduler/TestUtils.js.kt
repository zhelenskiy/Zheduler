package com.zhelenskiy.zheduler.zheduler

internal actual fun logCleanupError(e: Exception) {
    console.log("Error closing driver during cleanup", e)
}
