package com.zhelenskiy.zheduler.zheduler.db

/**
 * Opens the app's [ZhedulerDatabase] at the platform's canonical location.
 *
 * Each implementation points Room at exactly the file the SQLDelight drivers used, so an existing
 * installation keeps its data.
 */
expect class DatabaseFactory {
    fun createDatabase(): ZhedulerDatabase
}
