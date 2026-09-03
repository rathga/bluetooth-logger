package com.nestegg.btlogger.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object SyncScheduler {

    private const val PERIODIC_UNIQUE_NAME = "drive-sync"
    private const val PERIOD_HOURS = 1L

    fun ensureScheduled(context: Context) =
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)

    fun forceReenqueue(context: Context) =
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInputData(triggerData(SyncTrigger.MANUAL))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun enqueuePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        // No network constraint: its VALIDATED NetworkRequest is the suspect in issue #4's stall.
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInputData(triggerData(SyncTrigger.PERIODIC))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_UNIQUE_NAME, policy, request)
    }

    private fun triggerData(trigger: SyncTrigger) =
        workDataOf(DriveSyncWorker.KEY_TRIGGER to trigger.wireName)
}
