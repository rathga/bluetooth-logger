package com.nestegg.btlogger.sync

const val SYNC_STALE_THRESHOLD_MILLIS = 6L * 60 * 60 * 1000

// Caller must gate on there having been at least one attempt, or a fresh install reads as stale.
fun isSyncStale(
    nowMillis: Long,
    lastSuccessMillis: Long,
    thresholdMillis: Long = SYNC_STALE_THRESHOLD_MILLIS,
): Boolean = (nowMillis - lastSuccessMillis) >= thresholdMillis
