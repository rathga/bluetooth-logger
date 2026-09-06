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
import java.util.concurrent.Semaphore

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val syncState: SyncState by lazy { SyncState.from(applicationContext) }

    private val journal: SyncJournal by lazy { SyncJournal(applicationContext) }

    override suspend fun doWork(): Result {
        val trigger = SyncTrigger.fromWireName(inputData.getString(KEY_TRIGGER))

        val (setup, networkValidated) = try {
            readSetupStatus(applicationContext) to isActiveNetworkValidated(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read device state; the run is journalled and abandoned", e)
            return record(
                SyncAttempt(
                    utcTimestamp = System.currentTimeMillis(),
                    trigger = trigger,
                    outcome = SyncOutcome.ERROR,
                    rowsUploaded = 0,
                    errorClass = e.javaClass.simpleName,
                    batteryExempt = null,
                    networkValidated = null,
                ),
                Result.failure(),
            )
        }

        fun attemptFor(outcome: SyncOutcome, rowsUploaded: Int, errorClass: String?) = SyncAttempt(
            utcTimestamp = System.currentTimeMillis(),
            trigger = trigger,
            outcome = outcome,
            rowsUploaded = rowsUploaded,
            errorClass = errorClass,
            batteryExempt = setup.batteryExempt,
            networkValidated = networkValidated,
        )

        fun runSync(): Result {
            SetupNotifier.update(applicationContext, setup)

            val store = EventStore(applicationContext)
            maybeWriteHeartbeat(store, setup)

            fun recordAuthNeeded(e: Exception, rowsUploaded: Int): Result {
                Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
                SetupNotifier.notifyAuthNeeded(applicationContext)
                return record(
                    attemptFor(SyncOutcome.AUTH_FAILURE, rowsUploaded, e.javaClass.simpleName),
                    Result.failure(),
                )
            }

            val accountName = syncState.accountName
                ?: return record(attemptFor(SyncOutcome.NO_ACCOUNT, 0, null), Result.success())

            val months = store.months()
            if (months.isEmpty()) return record(attemptFor(SyncOutcome.NO_EVENTS, 0, null), Result.success())

            val (rowsUploaded, failure) = uploadPendingMonths(store, accountName, months)

            if (failure == null) {
                val outcome = if (rowsUploaded > 0) SyncOutcome.SUCCESS else SyncOutcome.NO_EVENTS
                return record(attemptFor(outcome, rowsUploaded, null), Result.success())
            }

            return when (failure) {
                is UserRecoverableAuthIOException, is UserRecoverableAuthException ->
                    recordAuthNeeded(failure, rowsUploaded)
                is IOException -> {
                    Log.w(TAG, "Transient sync failure; the next scheduled run picks it up", failure)
                    record(
                        attemptFor(SyncOutcome.IO_RETRY, rowsUploaded, failure.javaClass.simpleName),
                        retryOrFail(trigger.unattended),
                    )
                }
                else -> {
                    Log.e(TAG, "Sync failed", failure)
                    record(
                        attemptFor(SyncOutcome.ERROR, rowsUploaded, failure.javaClass.simpleName),
                        Result.failure(),
                    )
                }
            }
        }

        return try {
            if (!syncInFlight.tryAcquire()) {
                Log.i(TAG, "A sync is already in flight; journalling the skip")
                journal.append(attemptFor(SyncOutcome.ALREADY_RUNNING, 0, null))
                return retryOrFail(trigger.unattended)
            }
            try {
                runSync()
            } finally {
                syncInFlight.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync aborted before completion", e)
            record(attemptFor(SyncOutcome.ERROR, 0, e.javaClass.simpleName), Result.failure())
        }
    }

    private fun retryOrFail(unattended: Boolean): Result =
        if (unattended) Result.retry() else Result.failure()

    private data class UploadReport(val rowsUploaded: Int, val failure: Exception?)

    private fun uploadPendingMonths(
        store: EventStore,
        accountName: String,
        months: List<String>,
    ): UploadReport {
        val (client, deviceTag) = driveClientAndTag(accountName)
        var totalAppended = 0
        for (yearMonth in months) {
            try {
                val offset = syncState.offsetFor(accountName, yearMonth)
                val chunk = store.unsynced(yearMonth, offset)
                if (chunk.events.isEmpty()) continue
                val rows = chunk.events.map(CsvFormat::row)
                totalAppended += client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
                syncState.setOffsetFor(accountName, yearMonth, chunk.newByteOffset)
                Log.i(TAG, "Appended ${rows.size} row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
            } catch (e: Exception) {
                return UploadReport(totalAppended, e)
            }
        }
        return UploadReport(totalAppended, null)
    }

    private fun record(attempt: SyncAttempt, result: Result): Result {
        persistAndUpload(attempt)
        return result
    }

    private fun persistAndUpload(attempt: SyncAttempt) {
        fun uploadDiagnostics() {
            val accountName = syncState.accountName ?: return
            val rows = journal.retainedAttempts().map(CsvFormat::diagnosticsRow)
            if (rows.isEmpty()) return
            runCatching {
                val (client, deviceTag) = driveClientAndTag(accountName)
                client.overwriteCsv(
                    CsvFormat.diagnosticsFileName(deviceTag),
                    CsvFormat.DIAGNOSTICS_HEADER,
                    rows,
                )
            }.onFailure { Log.w(TAG, "Diagnostics upload failed; will retry next sync", it) }
        }

        syncState.recordAttempt(attempt)
        journal.append(attempt)
        if (attempt.outcome.isClean) {
            SetupNotifier.clearAuthNeeded(applicationContext)
            SetupNotifier.clearSyncAlert(applicationContext)
        }
        uploadDiagnostics()
    }

    private fun driveClientAndTag(accountName: String): Pair<DriveClient, String> =
        DriveClient.forAccountName(applicationContext, accountName) to
            DeviceTag.forContext(applicationContext)

    private fun maybeWriteHeartbeat(store: EventStore, setup: SetupStatus) {
        val now = System.currentTimeMillis()
        if (!shouldEmitHeartbeat(now, store.lastRecordMillis())) return
        val status = heartbeatStatus(setup, isBluetoothAdapterEnabled(applicationContext))
        val statusToken = CsvFormat.heartbeatStatusToken(status)
        store.append(BtEvent(now, EventType.HEARTBEAT, statusToken, ""))
        Log.i(TAG, "Wrote liveness heartbeat: $statusToken")
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "DriveSyncWorker"
        private val syncInFlight = Semaphore(1)
    }
}
