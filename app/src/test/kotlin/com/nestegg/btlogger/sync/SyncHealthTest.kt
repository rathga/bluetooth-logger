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
    private val graceWindow: Duration = SYNC_PERIOD.multipliedBy(3)
    private val forcedOutsideTheGraceWindow: Instant = now - graceWindow
    private val forcedJustInsideTheGraceWindow: Instant = now - graceWindow.minusMillis(1)
    private val longSinceSucceeded: Instant = now - SYNC_STALE_THRESHOLD.multipliedBy(3)
    private val justInsideTheThreshold: Instant = now - SYNC_STALE_THRESHOLD.minusMillis(1)

    @Test fun `sync goes stale six hours after the last success`() {
        assertEquals(Duration.ofHours(6), SYNC_STALE_THRESHOLD)
    }

    @Test fun `the sync job is asked to run hourly`() {
        assertEquals(Duration.ofHours(1), SYNC_PERIOD)
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

    @Test fun `a phone with no validated network that synced recently is still healthy`() {
        assertEquals(
            SyncHealth.HEALTHY,
            health(network = NetworkStatus.UNVALIDATED, lastSuccess = justInsideTheThreshold),
        )
    }

    @Test fun `a stale sync whose last attempt failed on auth asks for a new sign-in`() {
        assertEquals(
            SyncHealth.AUTH_EXPIRED,
            health(lastSuccess = longSinceSucceeded, lastOutcome = SyncOutcome.AUTH_FAILURE),
        )
    }

    @Test fun `an offline phone whose last attempt failed on auth still asks for a new sign-in`() {
        assertEquals(
            SyncHealth.AUTH_EXPIRED,
            health(
                network = NetworkStatus.UNVALIDATED,
                lastSuccess = longSinceSucceeded,
                lastOutcome = SyncOutcome.AUTH_FAILURE,
            ),
        )
    }

    @Test fun `an auth failure that has not yet gone stale is healthy`() {
        assertEquals(
            SyncHealth.HEALTHY,
            health(lastSuccess = justInsideTheThreshold, lastOutcome = SyncOutcome.AUTH_FAILURE),
        )
    }

    @Test fun `a stale sync whose last attempt failed on the network is not an auth problem`() {
        assertEquals(
            SyncHealth.STALLED,
            health(lastSuccess = longSinceSucceeded, lastOutcome = SyncOutcome.IO_RETRY),
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

    @Test fun `a force one millisecond inside the grace window is left to take effect`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedJustInsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a force that has only just happened is left to take effect`() {
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
                lastForcedReenqueue = forcedJustInsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a force exactly at the end of the grace window escalates to an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
            recovery(
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `an offline phone past the grace window is alerted and forced all the same`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
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
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
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

    @Test fun `a watchdog inside a sync run alerts instead of cancelling the job it runs under`() {
        assertEquals(
            SyncRecoveryAction.ALERT,
            recovery(runContext = SyncRunContext.INSIDE_SYNC_RUN, lastSuccess = longSinceSucceeded),
        )
    }

    @Test fun `a watchdog inside a sync run past the grace window still only alerts`() {
        assertEquals(
            SyncRecoveryAction.ALERT,
            recovery(
                runContext = SyncRunContext.INSIDE_SYNC_RUN,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a watchdog inside a sync run leaves a pending force its grace window`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(
                runContext = SyncRunContext.INSIDE_SYNC_RUN,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedJustInsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a watchdog inside a sync run stays silent while the app is open`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(
                visibility = AppVisibility.FOREGROUND,
                runContext = SyncRunContext.INSIDE_SYNC_RUN,
                lastSuccess = longSinceSucceeded,
            ),
        )
    }

    @Test fun `a watchdog inside a sync run on a healthy sync does nothing`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(
                runContext = SyncRunContext.INSIDE_SYNC_RUN,
                lastSuccess = justInsideTheThreshold,
            ),
        )
    }

    @Test fun `a watchdog inside a sync run with no sign-in clears the alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            recovery(
                runContext = SyncRunContext.INSIDE_SYNC_RUN,
                signedInSince = signedOut,
                lastSuccess = longSinceSucceeded,
            ),
        )
    }

    private fun health(
        network: NetworkStatus = NetworkStatus.VALIDATED,
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
        lastOutcome: SyncOutcome? = null,
    ): SyncHealth = syncHealth(
        network = network,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastOutcome = lastOutcome,
    )

    private fun recovery(
        network: NetworkStatus = NetworkStatus.VALIDATED,
        visibility: AppVisibility = AppVisibility.BACKGROUND,
        runContext: SyncRunContext = SyncRunContext.OUTSIDE_SYNC_RUN,
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
        lastOutcome: SyncOutcome? = null,
        lastForcedReenqueue: Instant = neverForced,
    ): SyncRecoveryAction = syncRecoveryAction(
        network = network,
        visibility = visibility,
        runContext = runContext,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastOutcome = lastOutcome,
        lastForcedReenqueue = lastForcedReenqueue,
    )
}
