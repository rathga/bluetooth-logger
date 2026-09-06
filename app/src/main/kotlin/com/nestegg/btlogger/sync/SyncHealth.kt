package com.nestegg.btlogger.sync

import java.time.Duration
import java.time.Instant

internal val SYNC_STALE_THRESHOLD: Duration = Duration.ofHours(6)

private val FORCED_REENQUEUE_GRACE: Duration = Duration.ofHours(1)

/**
 * [signedInSince] is null on a signed-out install, where there is nothing to be stale about:
 * the fact of being signed in is carried by the value, not left to each caller to check first.
 */
internal fun isSyncStale(
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    threshold: Duration = SYNC_STALE_THRESHOLD,
): Boolean =
    signedInSince != null && Duration.between(maxOf(lastSuccess, signedInSince), now) >= threshold

internal enum class SyncRecoveryAction {
    NONE,
    CLEAR_ALERT,
    FORCE_REENQUEUE,
    FORCE_REENQUEUE_AND_ALERT_STALLED,
    FORCE_REENQUEUE_AND_ALERT_OFFLINE,
}

internal fun syncRecoveryAction(
    networkValidated: Boolean,
    appInForeground: Boolean,
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    lastForcedReenqueue: Instant,
): SyncRecoveryAction = when {
    signedInSince == null -> SyncRecoveryAction.CLEAR_ALERT
    !isSyncStale(now, signedInSince, lastSuccess) -> SyncRecoveryAction.NONE
    lastForcedReenqueue <= lastSuccess -> SyncRecoveryAction.FORCE_REENQUEUE
    Duration.between(lastForcedReenqueue, now) < FORCED_REENQUEUE_GRACE -> SyncRecoveryAction.NONE
    // The alert exists to surface a stall that has no other symptom. On screen the sync-health
    // banner is already saying it, so the force still happens and the notification does not.
    appInForeground -> SyncRecoveryAction.FORCE_REENQUEUE
    networkValidated -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED
    else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE
}
