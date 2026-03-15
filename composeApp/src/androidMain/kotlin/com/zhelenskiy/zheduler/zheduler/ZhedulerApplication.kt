package com.zhelenskiy.zheduler.zheduler

import android.app.Application
import ca.gosyer.appdirs.impl.attachAppDirs

class ZhedulerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        attachAppDirs()
    }
}
