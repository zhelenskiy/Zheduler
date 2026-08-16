package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual class DatabaseFactory {
    actual fun createDatabase(): ZhedulerDatabase =
        Room.databaseBuilder<ZhedulerDatabase>(name = databaseFilePath())
            .setDriver(BundledSQLiteDriver())
            .withZhedulerMigrations()
            .build()
}

/**
 * The path SQLiter used, and therefore where an installation's existing `zheduler.db` is:
 * `<Application Support>/databases/<name>` (see SQLiter's `DatabaseFileContext.iosDirPath`).
 * SQLiter also created the directory on demand; Room does not, so do it here.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun databaseFilePath(): String {
    val applicationSupport =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
    val databaseDirectory = "$applicationSupport/databases"

    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(databaseDirectory)) {
        fileManager.createDirectoryAtPath(databaseDirectory, true, null, null)
    }

    return "$databaseDirectory/$DATABASE_FILE_NAME"
}
