package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.ExecutionException

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
            Log.w(TAG, "Forced a fresh sync job registration ($action)")
        }

        when (action) {
            SyncRecoveryAction.FORCE_REENQUEUE,
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT -> forceReenqueue()
            SyncRecoveryAction.NONE,
            SyncRecoveryAction.CANCEL_ALERT,
            SyncRecoveryAction.ALERT -> Unit
        }
    }

    fun syncNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        val pending = manager.getWorkInfosForUniqueWork(MANUAL_UNIQUE_NAME)

        fun recordTapProducedNoRun(step: String, thrown: Throwable) {
            val cause = (thrown as? ExecutionException)?.cause ?: thrown
            Log.e(TAG, "$step failed; this tap enqueued no sync", cause)
            runCatching {
                SyncState.from(context).recordAttempt(
                    SyncAttempt(
                        utcTimestamp = System.currentTimeMillis(),
                        trigger = SyncTrigger.MANUAL,
                        outcome = SyncOutcome.ERROR,
                        rowsUploaded = 0,
                        errorClass = cause.javaClass.simpleName,
                        batteryExempt = null,
                        networkValidated = null,
                    ),
                )
            }.onFailure { Log.e(TAG, "Could not record the failed manual sync", it) }
        }

        fun Operation.reportFailure(step: String) {
            result.addListener(
                { runCatching { result.get() }.onFailure { recordTapProducedNoRun(step, it) } },
                context.mainExecutor,
            )
        }

        fun replaceAnyUndispatchedRequest() {
            runCatching {
                val undispatched = pending.get().any {
                    !it.state.isFinished && it.state != WorkInfo.State.RUNNING
                }
                if (undispatched) {
                    manager.cancelUniqueWork(MANUAL_UNIQUE_NAME)
                        .reportFailure("Cancelling the undispatched manual sync")
                }
                val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                    .setInputData(triggerData(SyncTrigger.MANUAL.wireName))
                    .build()
                manager.enqueueUniqueWork(MANUAL_UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
                    .reportFailure("Enqueuing the manual sync")
            }.onFailure { recordTapProducedNoRun("Reading the pending manual sync", it) }
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
