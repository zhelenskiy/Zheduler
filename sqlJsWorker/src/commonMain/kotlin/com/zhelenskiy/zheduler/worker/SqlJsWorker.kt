package com.zhelenskiy.zheduler.worker

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

expect fun createSqlJsWorker(): WebWorkerSQLiteDriver
