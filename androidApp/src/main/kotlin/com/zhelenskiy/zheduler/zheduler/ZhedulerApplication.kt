package com.zhelenskiy.zheduler.zheduler

import android.app.Application
import ca.gosyer.appdirs.impl.attachAppDirs
import com.zhelenskiy.zheduler.zheduler.di.initAndroidDependencies
import com.zhelenskiy.zheduler.zheduler.events.ensureSweepScheduled

class ZhedulerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        attachAppDirs()
        // Here rather than in the activity: a worker or a boot receiver starts the process with no
        // activity at all, and everything it goes on to build — the database, and through it the
        // whole graph — needs the application first.
        initAndroidDependencies(this)
        ensureSweepScheduled(this)
    }
}
