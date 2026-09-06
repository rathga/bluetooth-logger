package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.setup.isActiveNetworkValidated
import java.time.Instant

private const val TAG = "SyncWatchdog"

internal fun recoverStalledSync(context: Context) {
    val syncState = SyncState.from(context)
    val action = syncRecoveryAction(
        signedIn = syncState.accountName != null,
        networkValidated = isActiveNetworkValidated(context),
        now = Instant.now(),
        signedInSince = Instant.ofEpochMilli(syncState.signedInSinceMillis),
        lastSuccess = Instant.ofEpochMilli(syncState.lastSuccessMillis),
        lastForcedReenqueue = Instant.ofEpochMilli(syncState.lastForcedReenqueueMillis),
    )

    fun forceReenqueue() {
        SyncScheduler.forceReenqueue(context)
        Log.w(TAG, "Sync is stale — forced a fresh sync job registration ($action)")
    }

    when (action) {
        SyncRecoveryAction.NONE -> Unit
        SyncRecoveryAction.CLEAR_ALERT -> SetupNotifier.clearSyncAlert(context)
        SyncRecoveryAction.FORCE_REENQUEUE -> forceReenqueue()
        SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED -> {
            forceReenqueue()
            SetupNotifier.notifySyncStalled(context)
        }
        SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE -> {
            forceReenqueue()
            SetupNotifier.notifySyncOffline(context)
        }
    }
}
