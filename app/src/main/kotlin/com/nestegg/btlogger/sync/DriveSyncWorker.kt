package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.setup.SetupStatus
import com.nestegg.btlogger.setup.isBluetoothAdapterEnabled
import com.nestegg.btlogger.setup.readSetupStatus
import com.nestegg.btlogger.storage.BtEvent
import com.nestegg.btlogger.storage.EventStore
import com.nestegg.btlogger.storage.EventType
import java.io.IOException

/**
 * Periodic worker that uploads new event rows to a CSV in Drive.
 * Run hourly with NetworkType.CONNECTED constraint.
 *
 * Idempotency: per-month last-synced byte offset persisted via [SyncState].
 * Re-running the worker after a successful upload is a no-op.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val setup = readSetupStatus(applicationContext)
        SetupNotifier.update(applicationContext, setup)

        val store = EventStore(applicationContext)
        maybeWriteHeartbeat(store, setup)

        val state = SyncState.from(applicationContext)
        val accountName = state.accountName ?: run {
            Log.i(TAG, "No Google account configured; skipping sync")
            return Result.success()
        }

        val months = store.months()
        if (months.isEmpty()) return Result.success()

        val client = DriveClient.forAccountName(applicationContext, accountName)
        val deviceTag = DeviceTag.forContext(applicationContext)
        var totalAppended = 0

        return try {
            for (yearMonth in months) {
                val offset = state.offsetFor(yearMonth)
                val chunk = store.unsynced(yearMonth, offset)
                if (chunk.events.isEmpty()) continue
                val rows = chunk.events.map(CsvFormat::row)
                val appended = client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
                state.setOffsetFor(yearMonth, chunk.newByteOffset)
                totalAppended += appended
                Log.i(TAG, "Appended $appended row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
            }
            state.lastSyncMillis = System.currentTimeMillis()
            Result.success()
        } catch (e: UserRecoverableAuthIOException) {
            // Token expired or scope was revoked. The offset is untouched, so the
            // next sync after re-auth will pick up exactly where we left off.
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            Result.failure()
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            Result.failure()
        } catch (e: IOException) {
            Log.w(TAG, "Transient sync failure; will retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure()
        }
    }

    /**
     * Write a liveness heartbeat if nothing has been logged for [HEARTBEAT_INTERVAL_MILLIS].
     * Runs before the account / empty-log early-outs so the first-ever heartbeat is
     * created even on a fresh install; it uploads on this or a later sync run.
     */
    private fun maybeWriteHeartbeat(store: EventStore, setup: SetupStatus) {
        val now = System.currentTimeMillis()
        if (!shouldEmitHeartbeat(now, store.lastRecordMillis())) return
        val status = heartbeatStatus(setup, isBluetoothAdapterEnabled(applicationContext))
        store.append(BtEvent(now, EventType.HEARTBEAT, status, ""))
        Log.i(TAG, "Wrote liveness heartbeat: $status")
    }

    companion object {
        const val UNIQUE_NAME = "drive-sync"
        private const val TAG = "DriveSyncWorker"
    }
}
