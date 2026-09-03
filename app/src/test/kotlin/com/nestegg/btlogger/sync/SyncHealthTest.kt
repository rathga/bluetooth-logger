package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHealthTest {

    private val now = 100L * SYNC_STALE_THRESHOLD_MILLIS
    private val neverForced = 0L

    @Test fun `fresh success is not stale`() {
        assertFalse(isSyncStale(now, lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1)))
    }

    @Test fun `exactly at the threshold is stale`() {
        assertTrue(isSyncStale(now, lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS))
    }

    @Test fun `well past the threshold is stale`() {
        assertTrue(isSyncStale(now, lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS))
    }

    @Test fun `a sync that is not stale needs no recovery`() {
        assertEquals(
            SyncRecoveryAction.NONE,
            syncRecoveryAction(
                signedIn = true,
                nowMillis = now,
                lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1),
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `stale with no prior force is forced silently`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                nowMillis = now,
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
                nowMillis = now,
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
                nowMillis = now,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - (FORCED_REENQUEUE_GRACE_MILLIS - 1),
            ),
        )
    }

    @Test fun `a force whose grace window has expired escalates to an alert`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE_AND_ALERT,
            syncRecoveryAction(
                signedIn = true,
                nowMillis = now,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - FORCED_REENQUEUE_GRACE_MILLIS,
            ),
        )
    }

    @Test fun `a signed-out install clears any standing stall alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                nowMillis = now,
                lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - FORCED_REENQUEUE_GRACE_MILLIS,
            ),
        )
    }

    @Test fun `a signed-out install with a fresh sync still clears the alert`() {
        assertEquals(
            SyncRecoveryAction.CLEAR_ALERT,
            syncRecoveryAction(
                signedIn = false,
                nowMillis = now,
                lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1),
                lastForcedReenqueueMillis = neverForced,
            ),
        )
    }

    @Test fun `a force older than the last success is silent again`() {
        assertEquals(
            SyncRecoveryAction.FORCE_REENQUEUE,
            syncRecoveryAction(
                signedIn = true,
                nowMillis = now,
                lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS,
                lastForcedReenqueueMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS,
            ),
        )
    }
}
