package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import com.nestegg.btlogger.setup.SetupNotifier

private const val TAG = "SyncWatchdog"

internal fun recoverStalledSync(context: Context) {
    val syncState = SyncState.from(context)
    if (syncState.accountName == null) return

    val now = System.currentTimeMillis()
    val alert = when (
        syncRecoveryAction(now, syncState.lastSuccessMillis, syncState.lastForcedReenqueueMillis)
    ) {
        SyncRecoveryAction.NONE -> return
        SyncRecoveryAction.FORCE_REENQUEUE -> false
        SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT -> true
    }

    SyncScheduler.forceReenqueue(context)
    syncState.recordForcedReenqueue(now)
    Log.w(TAG, "Sync is stale — forced a fresh sync job registration (alert=$alert)")

    if (alert) SetupNotifier.notifySyncStalled(context)
}
