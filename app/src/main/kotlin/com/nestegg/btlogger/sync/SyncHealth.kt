package com.nestegg.btlogger.sync

import java.time.Duration
import java.time.Instant

internal val SYNC_STALE_THRESHOLD: Duration = Duration.ofHours(6)

/** How often the periodic sync job is asked to run. */
internal val SYNC_PERIOD: Duration = Duration.ofHours(1)

/**
 * How long a forced re-enqueue is left to take effect before the watchdog escalates to an alert.
 * Written as a multiple of [SYNC_PERIOD] so the two cannot drift into equality: a forced job
 * restarts its interval at the force, so a grace of one period leaves it no headroom at all on the
 * phone whose periodic work the OS is already deferring — the stall the force exists to shake loose.
 */
private val FORCED_REENQUEUE_GRACE: Duration = SYNC_PERIOD.multipliedBy(3)

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
): SyncHealth {
    fun isSyncStale(): Boolean =
        signedInSince != null &&
            Duration.between(maxOf(lastSuccess, signedInSince), now) >= SYNC_STALE_THRESHOLD

    return when {
        !isSyncStale() -> SyncHealth.HEALTHY
        network == NetworkStatus.VALIDATED -> SyncHealth.STALLED
        else -> SyncHealth.OFFLINE
    }
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
    val health = syncHealth(
        network = network,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
    )

    return when {
        signedInSince == null -> SyncRecoveryAction.CLEAR_ALERT
        health == SyncHealth.HEALTHY -> SyncRecoveryAction.NONE
        lastForcedReenqueue <= lastSuccess -> SyncRecoveryAction.FORCE_REENQUEUE
        now >= lastForcedReenqueue && now < lastForcedReenqueue + FORCED_REENQUEUE_GRACE ->
            SyncRecoveryAction.NONE
        visibility == AppVisibility.FOREGROUND -> SyncRecoveryAction.FORCE_REENQUEUE
        health == SyncHealth.STALLED -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED
        else -> SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE
    }
}
