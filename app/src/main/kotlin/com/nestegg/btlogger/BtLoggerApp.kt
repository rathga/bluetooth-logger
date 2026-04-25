package com.nestegg.btlogger

import android.app.Application

class BtLoggerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: schedule DriveSyncWorker as a periodic WorkManager job here
    }
}
