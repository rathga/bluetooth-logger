package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

internal object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val PERIODIC_UNIQUE_NAME = "drive-sync"
    private const val MANUAL_UNIQUE_NAME = "drive-sync-manual"

    fun ensureScheduled(context: Context) =
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)

    fun reenqueueIfForced(context: Context, action: SyncRecoveryAction) {
        fun forceReenqueue() {
            enqueuePeriodic(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
            SyncState.from(context).recordForcedReenqueue()
            Log.w(TAG, "Sync is stale — forced a fresh sync job registration ($action)")
        }

        when (action) {
            SyncRecoveryAction.FORCE_REENQUEUE,
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT -> forceReenqueue()
            SyncRecoveryAction.NONE,
            SyncRecoveryAction.CLEAR_ALERT,
            SyncRecoveryAction.ALERT -> Unit
        }
    }

    fun syncNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        val pending = manager.getWorkInfosForUniqueWork(MANUAL_UNIQUE_NAME)

        fun replaceAnyUndispatchedRequest() {
            val undispatched = pending.get().any {
                !it.state.isFinished && it.state != WorkInfo.State.RUNNING
            }
            if (undispatched) manager.cancelUniqueWork(MANUAL_UNIQUE_NAME)
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setInputData(triggerData(SyncTrigger.MANUAL.wireName))
                .build()
            manager.enqueueUniqueWork(MANUAL_UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        pending.addListener({ replaceAnyUndispatchedRequest() }, context.mainExecutor)
    }

    private fun enqueuePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(SYNC_PERIOD)
            .setInputData(triggerData(SyncTrigger.PERIODIC.wireName))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_UNIQUE_NAME, policy, request)
    }

    private fun triggerData(triggerWireName: String) =
        workDataOf(DriveSyncWorker.KEY_TRIGGER to triggerWireName)
}
