package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SyncHealthTest {

    private val now: Instant = Instant.EPOCH + SYNC_STALE_THRESHOLD.multipliedBy(100)
    private val neverForced: Instant = Instant.EPOCH
    private val noSignInStamp: Instant = Instant.EPOCH
    private val neverSucceeded: Instant = Instant.EPOCH
    private val signedOut: Instant? = null
    private val signedInLongAgo: Instant = now - SYNC_STALE_THRESHOLD.multipliedBy(10)
    private val signedInMomentsAgo: Instant = now.minusSeconds(1)
    private val forcedOutsideTheGraceWindow: Instant = now - SYNC_STALE_THRESHOLD.multipliedBy(2)
    private val longSinceSucceeded: Instant = now - SYNC_STALE_THRESHOLD.multipliedBy(3)
    private val justInsideTheThreshold: Instant = now - SYNC_STALE_THRESHOLD.minusMillis(1)

    @Test fun `sync goes stale six hours after the last success`() {
        assertEquals(Duration.ofHours(6), SYNC_STALE_THRESHOLD)
    }

    @Test fun `a sync that is not stale is healthy`() {
        assertEquals(SyncHealth.HEALTHY, health(lastSuccess = justInsideTheThreshold))
    }

    @Test fun `exactly at the threshold the sync is no longer healthy`() {
        assertEquals(SyncHealth.STALLED, health(lastSuccess = now - SYNC_STALE_THRESHOLD))
    }

    @Test fun `an install signed in moments ago is healthy`() {
        assertEquals(
            SyncHealth.HEALTHY,
            health(signedInSince = signedInMomentsAgo, lastSuccess = neverSucceeded),
        )
    }

    @Test fun `an install signed in long ago that has never synced is unhealthy`() {
        assertEquals(SyncHealth.STALLED, health(lastSuccess = neverSucceeded))
    }

    @Test fun `an install with no recorded sign-in and no success is unhealthy`() {
        assertEquals(
            SyncHealth.STALLED,
            health(signedInSince = noSignInStamp, lastSuccess = neverSucceeded),
        )
    }

    @Test fun `a sign-in newer than the last success restarts the clock`() {
        assertEquals(
            SyncHealth.HEALTHY,
            health(signedInSince = signedInMomentsAgo, lastSuccess = longSinceSucceeded),
        )
    }

    @Test fun `a signed-out install is healthy however old the last success`() {
        assertEquals(
            SyncHealth.HEALTHY,
            health(signedInSince = signedOut, lastSuccess = neverSucceeded),
        )
    }

    @Test fun `a stale sync with a validated network reads as stalled`() {
        assertEquals(SyncHealth.STALLED, health(lastSuccess = longSinceSucceeded))
    }

    @Test fun `a stale sync with no validated network reads as offline`() {
        assertEquals(
            SyncHealth.OFFLINE,
            health(network = NetworkStatus.UNVALIDATED, lastSuccess = longSinceSucceeded),
        )
    }

    @Test fun `a sync that is not stale needs no recovery`() {
        assertEquals(SyncRecoveryAction.NONE, recovery(lastSuccess = justInsideTheThreshold))
    }

    @Test fun `an install signed in moments ago needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(signedInSince = signedInMomentsAgo, lastSuccess = neverSucceeded),
        )
    }

    @Test fun `an install whose worker has never run is recovered once the sign-in is old enough`() {
        assertEquals(SyncRecoveryAction.FORCE_REENQUEUE, recovery(lastSuccess = neverSucceeded))
    }

    @Test fun `stale with no prior force is forced silently`() {
        assertEquals(SyncRecoveryAction.FORCE_REENQUEUE, recovery(lastSuccess = longSinceSucceeded))
    }

    @Test fun `an offline phone stale with no prior force is forced all the same`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(network = NetworkStatus.UNVALIDATED, lastSuccess = longSinceSucceeded),
        )
    }

    @Test fun `stale exactly at the six-hour boundary is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(lastSuccess = now - SYNC_STALE_THRESHOLD),
        )
    }

    @Test fun `a force inside the grace window is left to take effect`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(lastSuccess = longSinceSucceeded, lastForcedReenqueue = now),
        )
    }

    @Test fun `an offline phone inside the grace window is still left alone`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(
                network = NetworkStatus.UNVALIDATED,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = now,
            ),
        )
    }

    @Test fun `a force whose grace window has expired escalates to a stall alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED,
            recovery(
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `an offline phone past the grace window is told it is waiting for a connection`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_OFFLINE,
            recovery(
                network = NetworkStatus.UNVALIDATED,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a stall the user is already looking at is forced without an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(
                visibility = AppVisibility.FOREGROUND,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `an offline stall the user is already looking at is forced without an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(
                network = NetworkStatus.UNVALIDATED,
                visibility = AppVisibility.FOREGROUND,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a signed-out install clears any standing stall alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            recovery(
                signedInSince = signedOut,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a signed-out install with a fresh sync still clears the alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            recovery(signedInSince = signedOut, lastSuccess = justInsideTheThreshold),
        )
    }

    @Test fun `a force stamped in the future cannot silence the watchdog`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT_STALLED,
            recovery(
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = now + SYNC_STALE_THRESHOLD,
            ),
        )
    }

    @Test fun `a force older than the last success is silent again`() {
        val lastSuccess = now - SYNC_STALE_THRESHOLD
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(
                lastSuccess = lastSuccess,
                lastForcedReenqueue = lastSuccess - SYNC_STALE_THRESHOLD,
            ),
        )
    }

    private fun health(
        network: NetworkStatus = NetworkStatus.VALIDATED,
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
    ): SyncHealth = syncHealth(
        network = network,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
    )

    private fun recovery(
        network: NetworkStatus = NetworkStatus.VALIDATED,
        visibility: AppVisibility = AppVisibility.BACKGROUND,
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
        lastForcedReenqueue: Instant = neverForced,
    ): SyncRecoveryAction = syncRecoveryAction(
        health = health(network = network, signedInSince = signedInSince, lastSuccess = lastSuccess),
        visibility = visibility,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastForcedReenqueue = lastForcedReenqueue,
    )
}
