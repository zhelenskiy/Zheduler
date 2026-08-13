package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

actual class DatabaseFactory {
    actual fun createDatabase(): ZhedulerDatabase {
        val dbFile = File(System.getProperty("user.home"), ".zheduler/$DATABASE_FILE_NAME")
        dbFile.parentFile?.mkdirs()

        return Room.databaseBuilder<ZhedulerDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
    }
}
