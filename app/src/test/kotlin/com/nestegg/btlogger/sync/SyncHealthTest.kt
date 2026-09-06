package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test fun `fresh success is not stale`() {
        assertFalse(stale(lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1)))
    }

    @Test fun `exactly at the threshold is stale`() {
        assertTrue(stale(lastSuccess = now - SYNC_STALE_THRESHOLD))
    }

    @Test fun `well past the threshold is stale`() {
        assertTrue(stale(lastSuccess = longSinceSucceeded))
    }

    @Test fun `an install signed in moments ago is not yet stale`() {
        assertFalse(stale(signedInSince = signedInMomentsAgo, lastSuccess = neverSucceeded))
    }

    @Test fun `an install signed in long ago that has never synced is stale`() {
        assertTrue(stale(lastSuccess = neverSucceeded))
    }

    @Test fun `an install with no recorded sign-in and no success is stale`() {
        assertTrue(stale(signedInSince = noSignInStamp, lastSuccess = neverSucceeded))
    }

    @Test fun `a sign-in newer than the last success restarts the clock`() {
        assertFalse(stale(signedInSince = signedInMomentsAgo, lastSuccess = longSinceSucceeded))
    }

    @Test fun `a signed-out install is never stale`() {
        assertFalse(stale(signedInSince = signedOut, lastSuccess = neverSucceeded))
    }

    @Test fun `a sync that is not stale needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            recovery(lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1)),
        )
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
            recovery(networkValidated = false, lastSuccess = longSinceSucceeded),
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
                networkValidated = false,
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
                networkValidated = false,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `a stall the user is already looking at is forced without an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(
                appInForeground = true,
                lastSuccess = longSinceSucceeded,
                lastForcedReenqueue = forcedOutsideTheGraceWindow,
            ),
        )
    }

    @Test fun `an offline stall the user is already looking at is forced without an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            recovery(
                networkValidated = false,
                appInForeground = true,
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
            recovery(
                signedInSince = signedOut,
                lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1),
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

    private fun stale(
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
    ): Boolean = isSyncStale(now, signedInSince = signedInSince, lastSuccess = lastSuccess)

    private fun recovery(
        networkValidated: Boolean = true,
        appInForeground: Boolean = false,
        signedInSince: Instant? = signedInLongAgo,
        lastSuccess: Instant,
        lastForcedReenqueue: Instant = neverForced,
    ): SyncRecoveryAction = syncRecoveryAction(
        networkValidated = networkValidated,
        appInForeground = appInForeground,
        now = now,
        signedInSince = signedInSince,
        lastSuccess = lastSuccess,
        lastForcedReenqueue = lastForcedReenqueue,
    )
}
