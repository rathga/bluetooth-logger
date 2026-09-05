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
    private val signedInLongAgo: Instant = now - SYNC_STALE_THRESHOLD.multipliedBy(10)
    private val signedInMomentsAgo: Instant = now.minusSeconds(1)

    @Test fun `fresh success is not stale`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1),
            ),
        )
    }

    @Test fun `exactly at the threshold is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD,
            ),
        )
    }

    @Test fun `well past the threshold is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
            ),
        )
    }

    @Test fun `an install signed in moments ago is not yet stale`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSince = signedInMomentsAgo,
                lastSuccess = neverSucceeded,
            ),
        )
    }

    @Test fun `an install signed in long ago that has never synced is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSince = signedInLongAgo,
                lastSuccess = neverSucceeded,
            ),
        )
    }

    @Test fun `an install with no recorded sign-in and no success is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSince = noSignInStamp,
                lastSuccess = neverSucceeded,
            ),
        )
    }

    @Test fun `a sign-in newer than the last success restarts the clock`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSince = signedInMomentsAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
            ),
        )
    }

    @Test fun `a sync that is not stale needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1),
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `an install signed in moments ago needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInMomentsAgo,
                lastSuccess = neverSucceeded,
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `an install whose worker has never run is recovered once the sign-in is old enough`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = neverSucceeded,
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `stale with no prior force is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `stale exactly at the six-hour boundary is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD,
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `a force inside the grace window is left to take effect`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
                lastForcedReenqueue = now,
            ),
        )
    }

    @Test fun `a force whose grace window has expired escalates to an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
                lastForcedReenqueue = now - SYNC_STALE_THRESHOLD.multipliedBy(2),
            ),
        )
    }

    @Test fun `a signed-out install clears any standing stall alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
                lastForcedReenqueue = now - SYNC_STALE_THRESHOLD.multipliedBy(2),
            ),
        )
    }

    @Test fun `a signed-out install with a fresh sync still clears the alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD.minusMillis(1),
                lastForcedReenqueue = neverForced,
            ),
        )
    }

    @Test fun `a force older than the last success is silent again`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                now = now,
                signedInSince = signedInLongAgo,
                lastSuccess = now - SYNC_STALE_THRESHOLD,
                lastForcedReenqueue = now - SYNC_STALE_THRESHOLD.multipliedBy(3),
            ),
        )
    }
}
