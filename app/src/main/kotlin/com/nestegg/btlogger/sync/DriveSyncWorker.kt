package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.nestegg.btlogger.setup.SetupNotifier
import com.nestegg.btlogger.setup.SetupStatus
import com.nestegg.btlogger.setup.isActiveNetworkValidated
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
 *
 * Every return path records a [SyncAttempt] in the [SyncJournal] and mirrors the
 * journal to Drive, so a stalled or failing sync is diagnosable after the fact.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trigger = SyncTrigger.fromWireName(inputData.getString(KEY_TRIGGER))
        val setup = readSetupStatus(applicationContext)
        SetupNotifier.update(applicationContext, setup)

        val store = EventStore(applicationContext)
        maybeWriteHeartbeat(store, setup)

        val networkValidated = isActiveNetworkValidated(applicationContext)

        fun record(outcome: SyncOutcome, rowsUploaded: Int, errorClass: String?, result: Result): Result {
            val attempt = SyncAttempt(
                utcTimestamp = System.currentTimeMillis(),
                trigger = trigger,
                outcome = outcome,
                rowsUploaded = rowsUploaded,
                errorClass = errorClass,
                batteryExempt = setup.batteryExempt,
                networkValidated = networkValidated,
            )
            persistAndUpload(attempt)
            return result
        }

        val state = SyncState.from(applicationContext)
        val accountName = state.accountName
            ?: return record(SyncOutcome.NO_ACCOUNT, 0, null, Result.success())

        val months = store.months()
        if (months.isEmpty()) return record(SyncOutcome.NO_EVENTS, 0, null, Result.success())

        val client = DriveClient.forAccountName(applicationContext, accountName)
        val deviceTag = DeviceTag.forContext(applicationContext)

        return try {
            var totalAppended = 0
            for (yearMonth in months) {
                val offset = state.offsetFor(yearMonth)
                val chunk = store.unsynced(yearMonth, offset)
                if (chunk.events.isEmpty()) continue
                val rows = chunk.events.map(CsvFormat::row)
                totalAppended += client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
                state.setOffsetFor(yearMonth, chunk.newByteOffset)
                Log.i(TAG, "Appended ${rows.size} row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
            }
            val outcome = if (totalAppended > 0) SyncOutcome.SUCCESS else SyncOutcome.NO_EVENTS
            record(outcome, totalAppended, null, Result.success())
        } catch (e: UserRecoverableAuthIOException) {
            // Token expired or scope was revoked. The offset is untouched, so the
            // next sync after re-auth will pick up exactly where we left off.
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            SetupNotifier.notifyAuthNeeded(applicationContext)
            record(SyncOutcome.AUTH_FAILURE, 0, e.javaClass.simpleName, Result.failure())
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            SetupNotifier.notifyAuthNeeded(applicationContext)
            record(SyncOutcome.AUTH_FAILURE, 0, e.javaClass.simpleName, Result.failure())
        } catch (e: IOException) {
            Log.w(TAG, "Transient sync failure; will retry", e)
            record(SyncOutcome.IO_RETRY, 0, e.javaClass.simpleName, Result.retry())
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            record(SyncOutcome.ERROR, 0, e.javaClass.simpleName, Result.failure())
        }
    }

    private fun persistAndUpload(attempt: SyncAttempt) {
        SyncState.from(applicationContext).recordAttempt(attempt)
        val journal = SyncJournal(applicationContext)
        journal.append(attempt)
        if (attempt.outcome.isClean) SetupNotifier.clearAuthNeeded(applicationContext)
        uploadDiagnostics(journal)
    }

    private fun uploadDiagnostics(journal: SyncJournal) {
        val accountName = SyncState.from(applicationContext).accountName ?: return
        val rows = journal.recentAttempts().map(CsvFormat::diagnosticsRow)
        if (rows.isEmpty()) return
        runCatching {
            val client = DriveClient.forAccountName(applicationContext, accountName)
            val deviceTag = DeviceTag.forContext(applicationContext)
            client.overwriteCsv(
                CsvFormat.diagnosticsFileName(deviceTag),
                CsvFormat.DIAGNOSTICS_HEADER,
                rows,
            )
        }.onFailure { Log.w(TAG, "Diagnostics upload failed; will retry next sync", it) }
    }

    private fun maybeWriteHeartbeat(store: EventStore, setup: SetupStatus) {
        val now = System.currentTimeMillis()
        if (!shouldEmitHeartbeat(now, store.lastRecordMillis())) return
        val status = heartbeatStatus(setup, isBluetoothAdapterEnabled(applicationContext))
        val statusToken = CsvFormat.heartbeatStatusToken(status)
        store.append(BtEvent(now, EventType.HEARTBEAT, statusToken, ""))
        Log.i(TAG, "Wrote liveness heartbeat: $statusToken")
    }

    companion object {
        const val UNIQUE_NAME = "drive-sync"
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "DriveSyncWorker"
    }
}
