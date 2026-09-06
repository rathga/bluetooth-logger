package com.nestegg.btlogger.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.nestegg.btlogger.setup.SetupNotifier
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

    private data class UploadReport(val rowsUploaded: Int, val failure: Exception?)

    override suspend fun doWork(): Result {
        val trigger = SyncTrigger.fromWireName(inputData.getString(KEY_TRIGGER))
        val setupReading = runCatching { readSetupStatus(applicationContext) }
        val networkValidated = runCatching { isActiveNetworkValidated(applicationContext) }.getOrNull()

        fun attempt(outcome: SyncOutcome, rowsUploaded: Int, errorClass: String?) = SyncAttempt(
            utcTimestamp = System.currentTimeMillis(),
            trigger = trigger,
            outcome = outcome,
            rowsUploaded = rowsUploaded,
            errorClass = errorClass,
            batteryExempt = setupReading.getOrNull()?.batteryExempt,
            networkValidated = networkValidated,
        )

        fun runSync(): Result {
            val store = EventStore(applicationContext)
            maybeWriteHeartbeat(
                store,
                setupReading.fold({ CapturePreconditions.Measured(it) }, { CapturePreconditions.Unreadable }),
            )

            val setup = setupReading.getOrElse { e ->
                Log.e(TAG, "Could not read setup state; the run is journalled and abandoned", e)
                return record(attempt(SyncOutcome.ERROR, 0, e.javaClass.simpleName), Result.failure())
            }

            if (!syncInFlight.tryAcquire()) {
                Log.i(TAG, "A sync is already in flight; journalling the skip")
                return journalOnly(
                    attempt(SyncOutcome.ALREADY_RUNNING, 0, null),
                    retryOrFail(trigger.unattended),
                )
            }

            try {
                SetupNotifier.update(applicationContext, setup)

                fun recordAuthNeeded(e: Exception, rowsUploaded: Int): Result {
                    Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
                    SetupNotifier.notifyAuthNeeded(applicationContext)
                    return record(
                        attempt(SyncOutcome.AUTH_FAILURE, rowsUploaded, e.javaClass.simpleName),
                        Result.failure(),
                    )
                }

                val account = syncState.signedInAccount
                    ?: return record(attempt(SyncOutcome.NO_ACCOUNT, 0, null), Result.success())

                val months = store.months()
                if (months.isEmpty()) {
                    return record(attempt(SyncOutcome.NO_EVENTS, 0, null), Result.success())
                }

                fun uploadPendingMonths(): UploadReport {
                    val (client, deviceTag) = driveClientAndTag(account.name)
                    var totalAppended = 0
                    for (yearMonth in months) {
                        try {
                            val chunk = store.unsynced(yearMonth, account.offsetFor(yearMonth))
                            if (chunk.events.isEmpty()) continue
                            val rows = chunk.events.map(CsvFormat::row)
                            totalAppended += client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
                            account.setOffsetFor(yearMonth, chunk.newByteOffset)
                            Log.i(TAG, "Appended ${rows.size} row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
                        } catch (e: Exception) {
                            return UploadReport(totalAppended, e)
                        }
                    }
                    return UploadReport(totalAppended, null)
                }

                val (rowsUploaded, failure) = uploadPendingMonths()

                if (failure == null) {
                    val outcome = if (rowsUploaded > 0) SyncOutcome.SUCCESS else SyncOutcome.NO_EVENTS
                    return record(attempt(outcome, rowsUploaded, null), Result.success())
                }

                return when (failure) {
                    is UserRecoverableAuthIOException, is UserRecoverableAuthException ->
                        recordAuthNeeded(failure, rowsUploaded)
                    is IOException -> {
                        Log.w(TAG, "Transient sync failure; the next scheduled run picks it up", failure)
                        record(
                            attempt(SyncOutcome.IO_RETRY, rowsUploaded, failure.javaClass.simpleName),
                            retryOrFail(trigger.unattended),
                        )
                    }
                    else -> {
                        Log.e(TAG, "Sync failed", failure)
                        record(
                            attempt(SyncOutcome.ERROR, rowsUploaded, failure.javaClass.simpleName),
                            Result.failure(),
                        )
                    }
                }
            } finally {
                syncInFlight.release()
            }
        }

        return try {
            runSync()
        } catch (e: Exception) {
            Log.e(TAG, "Sync aborted before completion", e)
            record(attempt(SyncOutcome.ERROR, 0, e.javaClass.simpleName), Result.failure())
        }
    }

    private fun retryOrFail(unattended: Boolean): Result =
        if (unattended) Result.retry() else Result.failure()

    private fun record(attempt: SyncAttempt, result: Result): Result {
        persistAndUpload(attempt)
        return result
    }

    private fun journalOnly(attempt: SyncAttempt, result: Result): Result {
        journal.append(attempt)
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

    private fun maybeWriteHeartbeat(store: EventStore, preconditions: CapturePreconditions) {
        val now = System.currentTimeMillis()
        if (!shouldEmitHeartbeat(now, store.lastRecordMillis())) return
        val status = heartbeatStatus(preconditions, isBluetoothAdapterEnabled(applicationContext))
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
