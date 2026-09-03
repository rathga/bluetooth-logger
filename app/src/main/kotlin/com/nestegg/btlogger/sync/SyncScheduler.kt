package com.nestegg.btlogger.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object SyncScheduler {

    private const val PERIODIC_UNIQUE_NAME = "drive-sync"
    private const val MANUAL_UNIQUE_NAME = "drive-sync-manual"
    private const val PERIOD_HOURS = 1L

    fun ensureScheduled(context: Context) =
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)

    fun forceReenqueue(context: Context) =
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInputData(triggerData(SyncTrigger.MANUAL.wireName))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun enqueuePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInputData(triggerData(SyncTrigger.PERIODIC.wireName))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_UNIQUE_NAME, policy, request)
    }

    private fun triggerData(triggerWireName: String) =
        workDataOf(DriveSyncWorker.KEY_TRIGGER to triggerWireName)
}
