package com.nestegg.btlogger

import android.app.Application
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.sync.SyncScheduler

class BtLoggerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppForeground.register(this)
        SetupNotifier.createChannel(this)
        SyncScheduler.ensureScheduled(this)
    }
}
