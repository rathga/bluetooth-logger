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

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val syncState: SyncState by lazy { SyncState.from(applicationContext) }

    override suspend fun doWork(): Result {
        val trigger = SyncTrigger.fromWireName(inputData.getString(KEY_TRIGGER))
        return try {
            runSync(trigger)
        } catch (e: Exception) {
            // Guarantees even an unexpected early throw leaves a journal record.
            Log.e(TAG, "Sync aborted before completion", e)
            record(attempt(trigger, SyncOutcome.ERROR, 0, e.javaClass.simpleName), Result.failure())
        }
    }

    private fun runSync(trigger: SyncTrigger): Result {
        val setup = readSetupStatus(applicationContext)
        SetupNotifier.update(applicationContext, setup)

        val store = EventStore(applicationContext)
        maybeWriteHeartbeat(store, setup)

        val networkValidated = isActiveNetworkValidated(applicationContext)

        fun attemptFor(outcome: SyncOutcome, rowsUploaded: Int, errorClass: String?) =
            attempt(trigger, outcome, rowsUploaded, errorClass, setup.batteryExempt, networkValidated)

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
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            SetupNotifier.notifyAuthNeeded(applicationContext)
            record(attemptFor(SyncOutcome.AUTH_FAILURE, 0, e.javaClass.simpleName), Result.failure())
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive auth needs user action — open the app and sign in again", e)
            SetupNotifier.notifyAuthNeeded(applicationContext)
            record(attemptFor(SyncOutcome.AUTH_FAILURE, 0, e.javaClass.simpleName), Result.failure())
        } catch (e: IOException) {
            Log.w(TAG, "Transient sync failure; will retry", e)
            record(attemptFor(SyncOutcome.IO_RETRY, 0, e.javaClass.simpleName), Result.retry())
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
        batteryExempt: Boolean = false,
        networkValidated: Boolean = false,
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
        val journal = SyncJournal(applicationContext)
        journal.append(attempt)
        if (attempt.outcome.isClean) SetupNotifier.clearAuthNeeded(applicationContext)
        uploadDiagnostics(journal)
    }

    private fun uploadDiagnostics(journal: SyncJournal) {
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
        const val UNIQUE_NAME = "drive-sync"
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "DriveSyncWorker"
    }
}
