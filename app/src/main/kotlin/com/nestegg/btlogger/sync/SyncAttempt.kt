package com.nestegg.btlogger.sync

import com.nestegg.btlogger.storage.appendJsonString
import com.nestegg.btlogger.storage.extractBoolean
import com.nestegg.btlogger.storage.extractInt
import com.nestegg.btlogger.storage.extractLong
import com.nestegg.btlogger.storage.extractString
import com.nestegg.btlogger.storage.extractStringOrNull

enum class SyncTrigger(val wireName: String) {
    PERIODIC("periodic"),
    MANUAL("manual");

    companion object {
        fun fromWireName(raw: String?): SyncTrigger =
            entries.firstOrNull { it.wireName == raw } ?: PERIODIC
    }
}

enum class SyncOutcome(val wireName: String, val isClean: Boolean, val displayLabel: String) {
    SUCCESS("success", true, "Synced"),
    NO_EVENTS("no-events", true, "Up to date"),
    NO_ACCOUNT("no-account", false, "Not signed in"),
    AUTH_FAILURE("auth-failure", false, "Sign-in needed"),
    IO_RETRY("io-retry", false, "Network issue, will retry"),
    ERROR("error", false, "Error");

    companion object {
        fun fromWireName(raw: String?): SyncOutcome? = entries.firstOrNull { it.wireName == raw }
    }
}

/** One durable record of a single [DriveSyncWorker] run. */
data class SyncAttempt(
    val utcTimestamp: Long,
    val trigger: SyncTrigger,
    val outcome: SyncOutcome,
    val rowsUploaded: Int,
    val errorClass: String?,
    val batteryExempt: Boolean,
    val networkValidated: Boolean,
)

internal fun SyncAttempt.toJsonLine(): String = buildString {
    append('{')
    append("\"ts\":").append(utcTimestamp).append(',')
    append("\"trigger\":\"").append(trigger.wireName).append("\",")
    append("\"outcome\":\"").append(outcome.wireName).append("\",")
    append("\"rows\":").append(rowsUploaded).append(',')
    append("\"error\":")
    if (errorClass == null) append("null") else appendJsonString(errorClass)
    append(',')
    append("\"battery_exempt\":").append(batteryExempt).append(',')
    append("\"network_validated\":").append(networkValidated)
    append('}')
}

internal fun parseSyncAttemptOrNull(line: String): SyncAttempt? = runCatching {
    val ts = extractLong(line, "ts") ?: return null
    val trigger = SyncTrigger.fromWireName(extractString(line, "trigger"))
    val outcome = SyncOutcome.fromWireName(extractString(line, "outcome")) ?: return null
    val rows = extractInt(line, "rows") ?: return null
    val error = extractStringOrNull(line, "error")
    val batteryExempt = extractBoolean(line, "battery_exempt") ?: return null
    val networkValidated = extractBoolean(line, "network_validated") ?: return null
    SyncAttempt(ts, trigger, outcome, rows, error, batteryExempt, networkValidated)
}.getOrNull()
