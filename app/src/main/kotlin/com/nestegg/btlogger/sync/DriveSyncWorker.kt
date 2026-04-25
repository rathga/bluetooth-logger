package com.nestegg.btlogger.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic worker that uploads new event rows to a CSV in Drive.
 * Run hourly with NetworkType.CONNECTED constraint.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // TODO:
        //  1. Read unsynced events from EventStore (use last-synced offset from SharedPreferences)
        //  2. Append to monthly CSV in Drive (one file per month, e.g. bluetooth-log-2026-04.csv)
        //  3. Persist new offset
        return Result.success()
    }
}
