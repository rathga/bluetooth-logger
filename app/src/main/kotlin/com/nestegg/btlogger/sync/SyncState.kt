package com.nestegg.btlogger.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SyncState(private val prefs: SharedPreferences) {

    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT, value) }

    val lastAttemptMillis: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)

    val lastAttemptOutcome: SyncOutcome?
        get() = SyncOutcome.fromWireName(prefs.getString(KEY_LAST_OUTCOME, null))

    val lastSuccessMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)

    fun recordAttempt(attempt: SyncAttempt) {
        prefs.edit {
            putLong(KEY_LAST_ATTEMPT, attempt.utcTimestamp)
            putString(KEY_LAST_OUTCOME, attempt.outcome.wireName)
            if (attempt.outcome.isClean) putLong(KEY_LAST_SUCCESS, attempt.utcTimestamp)
        }
    }

    fun offsetFor(yearMonth: String): Long =
        prefs.getLong(offsetKey(yearMonth), 0L)

    fun setOffsetFor(yearMonth: String, byteOffset: Long) {
        prefs.edit { putLong(offsetKey(yearMonth), byteOffset) }
    }

    private fun offsetKey(yearMonth: String) = "offset_$yearMonth"

    companion object {
        private const val PREFS_NAME = "bt_logger_sync"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_LAST_ATTEMPT = "last_attempt_millis"
        private const val KEY_LAST_OUTCOME = "last_attempt_outcome"
        private const val KEY_LAST_SUCCESS = "last_success_millis"

        fun from(context: Context): SyncState =
            SyncState(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
