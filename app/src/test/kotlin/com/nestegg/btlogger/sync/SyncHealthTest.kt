package com.nestegg.btlogger.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHealthTest {

    private val now = 100L * SYNC_STALE_THRESHOLD_MILLIS

    @Test fun `fresh success is not stale`() {
        assertFalse(isSyncStale(now, lastSuccessMillis = now - (SYNC_STALE_THRESHOLD_MILLIS - 1)))
    }

    @Test fun `exactly at the threshold is stale`() {
        assertTrue(isSyncStale(now, lastSuccessMillis = now - SYNC_STALE_THRESHOLD_MILLIS))
    }

    @Test fun `well past the threshold is stale`() {
        assertTrue(isSyncStale(now, lastSuccessMillis = now - 3 * SYNC_STALE_THRESHOLD_MILLIS))
    }
}
