package com.nestegg.btlogger.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal class SyncAccount(
    val name: String,
    private val prefs: SharedPreferences,
) {

    fun offsetFor(yearMonth: String): Long = prefs.getLong(offsetKey(yearMonth), 0L)

    fun setOffsetFor(yearMonth: String, byteOffset: Long) {
        prefs.edit { putLong(offsetKey(yearMonth), byteOffset) }
    }

    private fun offsetKey(yearMonth: String) = OFFSET_PREFIX + yearMonth + ACCOUNT_SEPARATOR + name

    private companion object {
        const val OFFSET_PREFIX = "offset_"
        const val ACCOUNT_SEPARATOR = "|"
    }
}

class SyncState(private val prefs: SharedPreferences) {

    init {
        fun migrateLegacyOffsets() {
            val legacy = prefs.all.keys.filter { it.startsWith("offset_") && !it.contains("|") }
            if (legacy.isEmpty()) return
            val owner = prefs.getString("account_name", null)
            prefs.edit {
                legacy.forEach { key ->
                    if (owner != null) {
                        putLong("offset_${key.removePrefix("offset_")}|$owner", prefs.getLong(key, 0L))
                    }
                    remove(key)
                }
            }
        }

        migrateLegacyOffsets()
    }

    internal val accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)

    internal val signedInSinceMillis: Long?
        get() = accountName?.let { prefs.getLong(KEY_SIGNED_IN_SINCE, 0L) }

    internal val signedInAccount: SyncAccount?
        get() = accountName?.let { SyncAccount(it, prefs) }

    val lastAttemptMillis: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)

    val lastAttemptOutcome: SyncOutcome?
        get() = SyncOutcome.fromWireName(prefs.getString(KEY_LAST_OUTCOME, null))

    val lastSuccessMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)

    internal val lastForcedReenqueueMillis: Long
        get() = prefs.getLong(KEY_LAST_FORCED_REENQUEUE, 0L)

    internal fun recordSignIn(accountName: String) {
        val previousAccount = this.accountName
        if (previousAccount == accountName) return
        prefs.edit {
            if (previousAccount != null) clearSyncHealth()
            putString(KEY_ACCOUNT, accountName)
            putLong(KEY_SIGNED_IN_SINCE, System.currentTimeMillis())
        }
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

    internal fun recordForcedReenqueue() {
        prefs.edit { putLong(KEY_LAST_FORCED_REENQUEUE, System.currentTimeMillis()) }
    }

    companion object {
        private const val PREFS_NAME = "bt_logger_sync"
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
