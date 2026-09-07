package com.nestegg.btlogger.sync

import java.time.Duration
import java.time.Instant

internal val SYNC_STALE_THRESHOLD: Duration = Duration.ofHours(6)

internal val SYNC_PERIOD: Duration = Duration.ofHours(1)

private val FORCED_REENQUEUE_GRACE: Duration = SYNC_PERIOD.multipliedBy(3)

internal enum class NetworkStatus {
    VALIDATED,
    UNVALIDATED,
}

internal enum class AppVisibility {
    FOREGROUND,
    BACKGROUND,
}

internal enum class SyncRunContext {
    OUTSIDE_SYNC_RUN,
    INSIDE_SYNC_RUN,
}

internal enum class SyncHealth {
    HEALTHY,
    AUTH_EXPIRED,
    STALLED,
    OFFLINE,
}

internal fun syncHealth(
    network: NetworkStatus,
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    lastOutcome: SyncOutcome?,
): SyncHealth {
    fun isSyncStale(): Boolean =
        signedInSince != null &&
            Duration.between(maxOf(lastSuccess, signedInSince), now) >= SYNC_STALE_THRESHOLD

    return when {
        !isSyncStale() -> SyncHealth.HEALTHY
        lastOutcome == SyncOutcome.AUTH_FAILURE -> SyncHealth.AUTH_EXPIRED
        network == NetworkStatus.VALIDATED -> SyncHealth.STALLED
        else -> SyncHealth.OFFLINE
    }
}

internal enum class SyncRecoveryAction {
    NONE,
    CLEAR_ALERT,
    ALERT,
    FORCE_REENQUEUE,
    FORCE_REENQUEUE_AND_ALERT,
}

internal fun syncRecoveryAction(
    network: NetworkStatus,
    visibility: AppVisibility,
    runContext: SyncRunContext,
    now: Instant,
    signedInSince: Instant?,
    lastSuccess: Instant,
    lastOutcome: SyncOutcome?,
    lastForcedReenqueue: Instant,
): SyncRecoveryAction {
    val health = syncHealth(
        network = network,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastOutcome = lastOutcome,
    )

    return when {
        signedInSince == null -> SyncRecoveryAction.CLEAR_ALERT
        health == SyncHealth.HEALTHY -> SyncRecoveryAction.NONE
        now >= lastForcedReenqueue && now < lastForcedReenqueue + FORCED_REENQUEUE_GRACE ->
            SyncRecoveryAction.NONE
        runContext == SyncRunContext.INSIDE_SYNC_RUN ->
            if (visibility == AppVisibility.FOREGROUND) SyncRecoveryAction.NONE
            else SyncRecoveryAction.ALERT
        lastForcedReenqueue <= lastSuccess -> SyncRecoveryAction.FORCE_REENQUEUE
        visibility == AppVisibility.FOREGROUND -> SyncRecoveryAction.FORCE_REENQUEUE
        else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT
    }
}
