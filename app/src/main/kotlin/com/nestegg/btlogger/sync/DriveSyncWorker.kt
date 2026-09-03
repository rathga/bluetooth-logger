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
        var batteryExempt = false
        var networkValidated = false
        return try {
            val setup = readSetupStatus(applicationContext)
            batteryExempt = setup.batteryExempt
            networkValidated = isActiveNetworkValidated(applicationContext)

            if (!syncInFlight.tryAcquire()) {
                Log.i(TAG, "A sync is already in flight; journalling the skip")
                journal.append(
                    attempt(
                        trigger, SyncOutcome.ALREADY_RUNNING, 0, null, batteryExempt, networkValidated,
                    ),
                )
                return retryOrFail(trigger)
            }
            try {
                runSync(trigger, setup, networkValidated)
            } finally {
                syncInFlight.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync aborted before completion", e)
            val aborted = attempt(
                trigger, SyncOutcome.ERROR, 0, e.javaClass.simpleName, batteryExempt, networkValidated,
            )
            record(aborted, Result.failure())
        }
    }

    private fun retryOrFail(trigger: SyncTrigger): Result =
        if (trigger.unattended) Result.retry() else Result.failure()

    private fun runSync(trigger: SyncTrigger, setup: SetupStatus, networkValidated: Boolean): Result {
        SetupNotifier.update(applicationContext, setup)

        val store = EventStore(applicationContext)
        maybeWriteHeartbeat(store, setup)

        fun attemptFor(outcome: SyncOutcome, rowsUploaded: Int, errorClass: String?) =
            attempt(trigger, outcome, rowsUploaded, errorClass, setup.batteryExempt, networkValidated)

        fun recordAuthNeeded(e: Exception): Result {
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            SetupNotifier.notifyAuthNeeded(applicationContext)
            return record(attemptFor(SyncOutcome.AUTH_FAILURE, 0, e.javaClass.simpleName), Result.failure())
        }

        val accountName = syncState.accountName
            ?: return record(attemptFor(SyncOutcome.NO_ACCOUNT, 0, null), Result.success())

        val months = store.months()
        if (months.isEmpty()) return record(attemptFor(SyncOutcome.NO_EVENTS, 0, null), Result.success())

        val (client, deviceTag) = driveClientAndTag(accountName)

        return try {
            val totalAppended = uploadPendingMonths(store, client, deviceTag, months)
            val outcome = if (totalAppended > 0) SyncOutcome.SUCCESS else SyncOutcome.NO_EVENTS
            record(attemptFor(outcome, totalAppended, null), Result.success())
        } catch (e: UserRecoverableAuthIOException) {
            // Offset untouched, so the next sync after re-auth resumes where we stopped.
            recordAuthNeeded(e)
        } catch (e: UserRecoverableAuthException) {
            recordAuthNeeded(e)
        } catch (e: IOException) {
            Log.w(TAG, "Transient sync failure; the next scheduled run picks it up", e)
            record(attemptFor(SyncOutcome.IO_RETRY, 0, e.javaClass.simpleName), retryOrFail(trigger))
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            record(attemptFor(SyncOutcome.ERROR, 0, e.javaClass.simpleName), Result.failure())
        }
    }

    private fun uploadPendingMonths(
        store: EventStore,
        client: DriveClient,
        deviceTag: String,
        months: List<String>,
    ): Int {
        var totalAppended = 0
        for (yearMonth in months) {
            val offset = syncState.offsetFor(yearMonth)
            val chunk = store.unsynced(yearMonth, offset)
            if (chunk.events.isEmpty()) continue
            val rows = chunk.events.map(CsvFormat::row)
            totalAppended += client.appendCsvRows(yearMonth, deviceTag, CsvFormat.HEADER, rows)
            syncState.setOffsetFor(yearMonth, chunk.newByteOffset)
            Log.i(TAG, "Appended ${rows.size} row(s) to bluetooth-log-$deviceTag-$yearMonth.csv")
        }
        return totalAppended
    }

    private fun attempt(
        trigger: SyncTrigger,
        outcome: SyncOutcome,
        rowsUploaded: Int,
        errorClass: String?,
        batteryExempt: Boolean,
        networkValidated: Boolean,
    ) = SyncAttempt(
        utcTimestamp = System.currentTimeMillis(),
        trigger = trigger,
        outcome = outcome,
        rowsUploaded = rowsUploaded,
        errorClass = errorClass,
        batteryExempt = batteryExempt,
        networkValidated = networkValidated,
    )

    private fun record(attempt: SyncAttempt, result: Result): Result {
        persistAndUpload(attempt)
        return result
    }

    private fun persistAndUpload(attempt: SyncAttempt) {
        syncState.recordAttempt(attempt)
        journal.append(attempt)
        if (attempt.outcome.isClean) {
            SetupNotifier.clearAuthNeeded(applicationContext)
            SetupNotifier.clearSyncStalled(applicationContext)
        }
        uploadDiagnostics()
    }

    private fun uploadDiagnostics() {
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
