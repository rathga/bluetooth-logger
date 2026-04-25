package com.nestegg.btlogger.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted sync bookkeeping: which Google account to use, last-synced byte
 * offset per monthly file, and last successful sync wall-clock time.
 */
class SyncState(private val prefs: SharedPreferences) {

    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT, value) }

    var lastSyncMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

    fun offsetFor(yearMonth: String): Long =
        prefs.getLong(offsetKey(yearMonth), 0L)

    fun setOffsetFor(yearMonth: String, byteOffset: Long) {
        prefs.edit { putLong(offsetKey(yearMonth), byteOffset) }
    }

    private fun offsetKey(yearMonth: String) = "offset_$yearMonth"

    companion object {
        private const val PREFS_NAME = "bt_logger_sync"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_LAST_SYNC = "last_sync_millis"

        fun from(context: Context): SyncState =
            SyncState(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
