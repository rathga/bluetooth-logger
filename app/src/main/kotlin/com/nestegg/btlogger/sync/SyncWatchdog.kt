package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import com.nestegg.btlogger.AppForeground
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.setup.isActiveNetworkValidated
import java.time.Instant

private const val TAG = "SyncWatchdog"

private class LiveSyncReading(
    val now: Instant,
    val signedInSince: Instant?,
    val lastSuccess: Instant,
    val lastForcedReenqueue: Instant,
    val health: SyncHealth,
)

private fun readLiveSync(context: Context): LiveSyncReading {
    val syncState = SyncState.from(context)
    val now = Instant.now()
    val signedInSince = syncState.signedInSinceMillis?.let(Instant::ofEpochMilli)
    val lastSuccess = Instant.ofEpochMilli(syncState.lastSuccessMillis)
    val network =
        if (isActiveNetworkValidated(context)) NetworkStatus.VALIDATED else NetworkStatus.UNVALIDATED
    return LiveSyncReading(
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastForcedReenqueue = Instant.ofEpochMilli(syncState.lastForcedReenqueueMillis),
        health = syncHealth(network, now, signedInSince, lastSuccess),
    )
}

internal fun readSyncHealth(context: Context): SyncHealth = readLiveSync(context).health

internal fun recoverStalledSync(context: Context) {
    val live = readLiveSync(context)
    val action = syncRecoveryAction(
        health = live.health,
        visibility = if (AppForeground.isForeground) AppVisibility.FOREGROUND else AppVisibility.BACKGROUND,
        now = live.now,
        signedInSince = live.signedInSince,
        lastSuccess = live.lastSuccess,
        lastForcedReenqueue = live.lastForcedReenqueue,
    )

    fun forceReenqueue() {
        SyncScheduler.forceReenqueue(context)
        Log.w(TAG, "Sync is stale — forced a fresh sync job registration ($action)")
    }

    fun clearAlertsThisVerdictContradicts() {
        when (live.health) {
            SyncHealth.HEALTHY -> SetupNotifier.clearSyncAlert(context)
            SyncHealth.STALLED -> SetupNotifier.clearOfflineSyncAlert(context)
            SyncHealth.OFFLINE -> SetupNotifier.clearStalledSyncAlert(context)
        }
    }

    when (action) {
        SyncRecoveryAction.NONE -> clearAlertsThisVerdictContradicts()
        SyncRecoveryAction.CLEAR_ALERT -> SetupNotifier.clearSyncAlert(context)
        SyncRecoveryAction.FORCE_REENQUEUE -> {
            forceReenqueue()
            clearAlertsThisVerdictContradicts()
        }
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
