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
    val lastOutcome: SyncOutcome?,
    val lastForcedReenqueue: Instant,
    val network: NetworkStatus,
    val health: SyncHealth,
)

private fun readLiveSync(context: Context): LiveSyncReading {
    val syncState = SyncState.from(context)
    val now = Instant.now()
    val signedInSince = syncState.signedInSinceMillis?.let(Instant::ofEpochMilli)
    val lastSuccess = Instant.ofEpochMilli(syncState.lastSuccessMillis)
    val lastOutcome = syncState.lastAttemptOutcome
    val network =
        if (isActiveNetworkValidated(context)) NetworkStatus.VALIDATED else NetworkStatus.UNVALIDATED
    return LiveSyncReading(
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastOutcome = lastOutcome,
        lastForcedReenqueue = Instant.ofEpochMilli(syncState.lastForcedReenqueueMillis),
        network = network,
        health = syncHealth(
            network = network,
            now = now,
            signedInSince = signedInSince,
            lastSuccess = lastSuccess,
            lastOutcome = lastOutcome,
        ),
    )
}

internal fun readSyncHealth(context: Context): SyncHealth = readLiveSync(context).health

internal fun recoverStalledSync(context: Context) =
    runWatchdog(context, SyncRunContext.OUTSIDE_SYNC_RUN)

internal fun reportStalledSync(context: Context) =
    runWatchdog(context, SyncRunContext.INSIDE_SYNC_RUN)

private fun runWatchdog(context: Context, runContext: SyncRunContext) {
    runCatching {
        val live = readLiveSync(context)
        val action = syncRecoveryAction(
            network = live.network,
            visibility = if (AppForeground.isForeground) AppVisibility.FOREGROUND else AppVisibility.BACKGROUND,
            runContext = runContext,
            now = live.now,
            signedInSince = live.signedInSince,
            lastSuccess = live.lastSuccess,
            lastOutcome = live.lastOutcome,
            lastForcedReenqueue = live.lastForcedReenqueue,
        )

        fun forceReenqueue() {
            SyncScheduler.forceReenqueue(context)
            Log.w(TAG, "Sync is stale — forced a fresh sync job registration ($action)")
        }

        when (action) {
            SyncRecoveryAction.NONE ->
                SetupNotifier.retireSyncAlertsContradicting(context, live.health)
            SyncRecoveryAction.CLEAR_ALERT -> SetupNotifier.clearSyncAlerts(context)
            SyncRecoveryAction.ALERT -> SetupNotifier.notifySyncAlert(context, live.health)
            SyncRecoveryAction.FORCE_REENQUEUE -> {
                forceReenqueue()
                SetupNotifier.retireSyncAlertsContradicting(context, live.health)
            }
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT -> {
                forceReenqueue()
                SetupNotifier.notifySyncAlert(context, live.health)
            }
        }
    }.onFailure { Log.e(TAG, "Sync watchdog failed", it) }
}
