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
): SyncRecoveryAction = when {
    signedInSince == null -> SyncRecoveryAction.CLEAR_ALERT
    !isSyncStale(now, signedInSince, lastSuccess) -> SyncRecoveryAction.NONE
    lastForcedReenqueue <= lastSuccess -> SyncRecoveryAction.FORCE_REENQUEUE
    Duration.between(lastForcedReenqueue, now) < FORCED_REENQUEUE_GRACE -> SyncRecoveryAction.NONE
    visibility == AppVisibility.FOREGROUND -> SyncRecoveryAction.FORCE_REENQUEUE
    network == NetworkStatus.VALIDATED -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED
    else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE
}
