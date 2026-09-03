package com.nestegg.btlogger.sync

const val SYNC_STALE_THRESHOLD_MILLIS = 6L * 60 * 60 * 1000

internal const val FORCED_REENQUEUE_GRACE_MILLIS = 60L * 60 * 1000

// Caller must gate on there having been at least one attempt, or a fresh install reads as stale.
fun isSyncStale(
    nowMillis: Long,
    lastSuccessMillis: Long,
    thresholdMillis: Long = SYNC_STALE_THRESHOLD_MILLIS,
): Boolean = (nowMillis - lastSuccessMillis) >= thresholdMillis

internal enum class SyncRecoveryAction {
    NONE,
    CLEAR_ALERT,
    FORCE_REENQUEUE,
    FORCE_REENQUEUE_AND_ALERT,
}

internal fun syncRecoveryAction(
    signedIn: Boolean,
    nowMillis: Long,
    lastSuccessMillis: Long,
    lastForcedReenqueueMillis: Long,
): SyncRecoveryAction = when {
    !signedIn -> SyncRecoveryAction.CLEAR_ALERT
    !isSyncStale(nowMillis, lastSuccessMillis) -> SyncRecoveryAction.NONE
    lastForcedReenqueueMillis <= lastSuccessMillis -> SyncRecoveryAction.FORCE_REENQUEUE
    nowMillis - lastForcedReenqueueMillis < FORCED_REENQUEUE_GRACE_MILLIS -> SyncRecoveryAction.NONE
    else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT
}
