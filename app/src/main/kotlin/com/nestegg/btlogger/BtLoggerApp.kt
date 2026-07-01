package com.nestegg.btlogger

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.sync.DriveSyncWorker
import com.nestegg.btlogger.sync.SyncTrigger
import java.util.concurrent.TimeUnit

class BtLoggerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SetupNotifier.createChannel(this)
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(DriveSyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.wireName))
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DriveSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
