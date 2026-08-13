package com.zhelenskiy.zheduler.zheduler.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual class DatabaseFactory(private val context: Context) {
    actual fun createDatabase(): ZhedulerDatabase =
        // getDatabasePath() resolves to the same file AndroidSqliteDriver used for this name.
        // BundledSQLiteDriver brings its own SQLite build, which is where the JSON1 functions the
        // filter queries rely on come from (previously the reason for the requery dependency).
        Room.databaseBuilder<ZhedulerDatabase>(
            context = context.applicationContext,
            name = context.applicationContext.getDatabasePath(DATABASE_FILE_NAME).absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
}
