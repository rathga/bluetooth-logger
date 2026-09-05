package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import com.nestegg.btlogger.setup.SetupNotifier
import java.time.Instant

private const val TAG = "SyncWatchdog"

internal fun recoverStalledSync(context: Context) {
    val syncState = SyncState.from(context)
    val alert = when (
        syncRecoveryAction(
            signedIn = syncState.accountName != null,
            now = Instant.now(),
            signedInSince = Instant.ofEpochMilli(syncState.signedInSinceMillis),
            lastSuccess = Instant.ofEpochMilli(syncState.lastSuccessMillis),
            lastForcedReenqueue = Instant.ofEpochMilli(syncState.lastForcedReenqueueMillis),
        )
    ) {
        SyncRecoveryAction.NONE -> return
        SyncRecoveryAction.CLEAR_ALERT -> {
            SetupNotifier.clearSyncStalled(context)
            return
        }
        SyncRecoveryAction.FORCE_REENQUEUE -> false
        SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT -> true
    }

    SyncScheduler.forceReenqueue(context)
    Log.w(TAG, "Sync is stale — forced a fresh sync job registration (alert=$alert)")

    if (alert) SetupNotifier.notifySyncStalled(context)
}
