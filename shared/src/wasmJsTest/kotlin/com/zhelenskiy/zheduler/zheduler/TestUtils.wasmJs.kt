package com.zhelenskiy.zheduler.zheduler

internal actual fun logCleanupError(e: Exception) {
    println("Error closing driver during cleanup: $e")
}
