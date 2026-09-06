package com.nestegg.btlogger.sync

import java.time.Duration
import java.time.Instant

internal val SYNC_STALE_THRESHOLD: Duration = Duration.ofHours(6)

private val FORCED_REENQUEUE_GRACE: Duration = Duration.ofHours(1)

internal fun isSyncStale(
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    threshold: Duration = SYNC_STALE_THRESHOLD,
): Boolean =
    signedInSince != null && Duration.between(maxOf(lastSuccess, signedInSince), now) >= threshold

internal enum class NetworkStatus {
    VALIDATED,
    UNVALIDATED,
}

internal enum class AppVisibility {
    FOREGROUND,
    BACKGROUND,
}

internal enum class SyncHealth {
    HEALTHY,
    STALLED,
    OFFLINE,
}

internal fun syncHealth(
    network: NetworkStatus,
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
): SyncHealth = when {
    !isSyncStale(now, signedInSince, lastSuccess) -> SyncHealth.HEALTHY
    network == NetworkStatus.VALIDATED -> SyncHealth.STALLED
    else -> SyncHealth.OFFLINE
}

internal enum class SyncRecoveryAction {
    NONE,
    CLEAR_ALERT,
    FORCE_REENQUEUE,
    FORCE_REENQUEUE_AND_ALERT_STALLED,
    FORCE_REENQUEUE_AND_ALERT_OFFLINE,
}

internal fun syncRecoveryAction(
    network: NetworkStatus,
    visibility: AppVisibility,
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    lastForcedReenqueue: Instant,
): SyncRecoveryAction {
    val health = syncHealth(network, now, signedInSince, lastSuccess)
    return when {
        signedInSince == null -> SyncRecoveryAction.CLEAR_ALERT
        health == SyncHealth.HEALTHY -> SyncRecoveryAction.NONE
        lastForcedReenqueue <= lastSuccess -> SyncRecoveryAction.FORCE_REENQUEUE
        Duration.between(lastForcedReenqueue, now) < FORCED_REENQUEUE_GRACE -> SyncRecoveryAction.NONE
        visibility == AppVisibility.FOREGROUND -> SyncRecoveryAction.FORCE_REENQUEUE
        health == SyncHealth.STALLED -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED
        else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE
    }
}
