package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHealthTest {

    private val now = 100L * SYNC_STALE_THRESHOLD_MILLIS
    private val neverForced = 0L
    private val noSignInStamp = 0L
    private val neverSucceeded = 0L
    private val signedInLongAgo = now - 10 * SYNC_STALE_THRESHOLD_MILLIS
    private val signedInMomentsAgo = now - 1000

    @Test fun `fresh success is not stale`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1),
            ),
        )
    }

    @Test fun `exactly at the threshold is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `well past the threshold is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `an install signed in moments ago is not yet stale`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSinceMillis = signedInMomentsAgo,
                lastSuccessMillis = neverSucceeded,
            ),
        )
    }

    @Test fun `an install signed in long ago that has never synced is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = neverSucceeded,
            ),
        )
    }

    @Test fun `an install with no recorded sign-in and no success is stale`() {
        assertTrue(
            isSyncStale(
                now,
                signedInSinceMillis = noSignInStamp,
                lastSuccessMillis = neverSucceeded,
            ),
        )
    }

    @Test fun `a sign-in newer than the last success restarts the clock`() {
        assertFalse(
            isSyncStale(
                now,
                signedInSinceMillis = signedInMomentsAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `a sync that is not stale needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1),
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `an install signed in moments ago needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInMomentsAgo,
                lastSuccessMillis = neverSucceeded,
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `an install whose worker has never run is recovered once the sign-in is old enough`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = neverSucceeded,
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `stale with no prior force is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `stale exactly at the six-hour boundary is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `a force inside the grace window is left to take effect`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now,
            ),
        )
    }

    @Test fun `a force whose grace window has expired escalates to an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 2 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `an offline phone is never alerted, however stale`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = false,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 2 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `an offline phone is not forced either`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = false,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `a signed-out install clears any standing stall alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 2 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `a signed-out install with a fresh sync still clears the alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1),
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `a signed-out install clears the alert even with no network`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                networkValidated = false,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 2 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }

    @Test fun `a force older than the last success is silent again`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                networkValidated = true,
                nowMillis = now,
                signedInSinceMillis = signedInLongAgo,
                lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }
}
