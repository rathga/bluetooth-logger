package com.nestegg.btlogger.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SyncState(private val prefs: SharedPreferences) {

    init {
        adoptLegacyOffsets()
    }

    val accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)

    internal val signedInSinceMillis: Long
        get() = prefs.getLong(KEY_SIGNED_IN_SINCE, 0L)

    val lastAttemptMillis: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)

    val lastAttemptOutcome: SyncOutcome?
        get() = SyncOutcome.fromWireName(prefs.getString(KEY_LAST_OUTCOME, null))

    val lastSuccessMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)

    internal val lastForcedReenqueueMillis: Long
        get() = prefs.getLong(KEY_LAST_FORCED_REENQUEUE, 0L)

    internal fun recordSignIn(accountName: String, nowMillis: Long) {
        val previousAccount = prefs.getString(KEY_ACCOUNT, null)
        prefs.edit {
            if (previousAccount != null && previousAccount != accountName) clearSyncHealth()
            putString(KEY_ACCOUNT, accountName)
            putLong(KEY_SIGNED_IN_SINCE, nowMillis)
        }
        adoptLegacyOffsets()
    }

    internal fun recordSignOut() {
        prefs.edit {
            remove(KEY_ACCOUNT)
            remove(KEY_SIGNED_IN_SINCE)
            clearSyncHealth()
        }
    }

    private fun SharedPreferences.Editor.clearSyncHealth() {
        remove(KEY_LAST_ATTEMPT)
        remove(KEY_LAST_OUTCOME)
        remove(KEY_LAST_SUCCESS)
        remove(KEY_LAST_FORCED_REENQUEUE)
    }

    fun recordAttempt(attempt: SyncAttempt) {
        prefs.edit {
            putLong(KEY_LAST_ATTEMPT, attempt.utcTimestamp)
            putString(KEY_LAST_OUTCOME, attempt.outcome.wireName)
            if (attempt.outcome.isClean) putLong(KEY_LAST_SUCCESS, attempt.utcTimestamp)
        }
    }

    internal fun recordForcedReenqueue(nowMillis: Long) {
        prefs.edit { putLong(KEY_LAST_FORCED_REENQUEUE, nowMillis) }
    }

    fun offsetFor(accountName: String, yearMonth: String): Long =
        prefs.getLong(offsetKey(accountName, yearMonth), 0L)

    fun setOffsetFor(accountName: String, yearMonth: String, byteOffset: Long) {
        prefs.edit { putLong(offsetKey(accountName, yearMonth), byteOffset) }
    }

    private fun offsetKey(accountName: String, yearMonth: String) =
        OFFSET_PREFIX + yearMonth + ACCOUNT_SEPARATOR + accountName

    private fun adoptLegacyOffsets() {
        val owner = prefs.getString(KEY_ACCOUNT, null) ?: return
        val legacy = prefs.all.keys
            .filter { it.startsWith(OFFSET_PREFIX) && !it.contains(ACCOUNT_SEPARATOR) }
            .associateWith { prefs.getLong(it, 0L) }
        if (legacy.isEmpty()) return
        prefs.edit {
            legacy.forEach { (key, byteOffset) ->
                putLong(offsetKey(owner, key.removePrefix(OFFSET_PREFIX)), byteOffset)
                remove(key)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "bt_logger_sync"
        private const val OFFSET_PREFIX = "offset_"
        private const val ACCOUNT_SEPARATOR = "|"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_SIGNED_IN_SINCE = "signed_in_since_millis"
        private const val KEY_LAST_ATTEMPT = "last_attempt_millis"
        private const val KEY_LAST_OUTCOME = "last_attempt_outcome"
        private const val KEY_LAST_SUCCESS = "last_success_millis"
        private const val KEY_LAST_FORCED_REENQUEUE = "last_forced_reenqueue_millis"

        fun from(context: Context): SyncState =
            SyncState(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
