package com.nestegg.btlogger.sync

/** How long the app tolerates no *successful* sync before warning the user. */
const val SYNC_STALE_THRESHOLD_MILLIS = 6L * 60 * 60 * 1000

/**
 * True when the last successful sync is older than [thresholdMillis]. The caller
 * gates this on there having been at least one attempt against a signed-in
 * account, so a fresh install doesn't nag before the first run.
 */
fun isSyncStale(
    nowMillis: Long,
    lastSuccessMillis: Long,
    thresholdMillis: Long = SYNC_STALE_THRESHOLD_MILLIS,
): Boolean = (nowMillis - lastSuccessMillis) >= thresholdMillis
