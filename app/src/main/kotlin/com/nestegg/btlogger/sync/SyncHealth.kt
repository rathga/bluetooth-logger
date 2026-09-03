package com.nestegg.btlogger.sync

const val SYNC_STALE_THRESHOLD_MILLIS = 6L * 60 * 60 * 1000

private const val FORCED_REENQUEUE_GRACE_MILLIS = 60L * 60 * 1000

internal fun isSyncStale(
    nowMillis: Long,
    signedInSinceMillis: Long,
    lastSuccessMillis: Long,
    thresholdMillis: Long = SYNC_STALE_THRESHOLD_MILLIS,
): Boolean =
    (nowMillis - maxOf(lastSuccessMillis, signedInSinceMillis)) >= thresholdMillis

internal enum class SyncRecoveryAction {
    NONE,
    CLEAR_ALERT,
    FORCE_REENQUEUE,
    FORCE_REENQUEUE_AND_ALERT,
}

internal fun syncRecoveryAction(
    signedIn: Boolean,
    networkValidated: Boolean,
    nowMillis: Long,
    signedInSinceMillis: Long,
    lastSuccessMillis: Long,
    lastForcedReenqueueMillis: Long,
): SyncRecoveryAction = when {
    !signedIn -> SyncRecoveryAction.CLEAR_ALERT
    !networkValidated -> SyncRecoveryAction.NONE
    !isSyncStale(nowMillis, signedInSinceMillis, lastSuccessMillis) -> SyncRecoveryAction.NONE
    lastForcedReenqueueMillis <= lastSuccessMillis -> SyncRecoveryAction.FORCE_REENQUEUE
    nowMillis - lastForcedReenqueueMillis < FORCED_REENQUEUE_GRACE_MILLIS -> SyncRecoveryAction.NONE
    else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT
}
