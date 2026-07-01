package com.nestegg.btlogger.sync

import com.nestegg.btlogger.storage.BtEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object CsvFormat {

    const val HEADER = "utc_iso,event_type,device_name,device_mac"

    const val DIAGNOSTICS_HEADER =
        "utc_iso,trigger,outcome,rows_uploaded,error_class,battery_exempt,network_validated"

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun row(event: BtEvent): String = buildString {
        append(isoFormatter.format(Instant.ofEpochMilli(event.utcTimestamp)))
        append(',')
        append(event.eventType.name)
        append(',')
        append(csvField(event.deviceName ?: ""))
        append(',')
        append(csvField(event.deviceMac))
    }

    fun diagnosticsFileName(deviceTag: String): String = "sync-diagnostics-$deviceTag.csv"

    fun diagnosticsRow(attempt: SyncAttempt): String = buildString {
        append(isoFormatter.format(Instant.ofEpochMilli(attempt.utcTimestamp)))
        append(',')
        append(attempt.trigger.wireName)
        append(',')
        append(attempt.outcome.wireName)
        append(',')
        append(attempt.rowsUploaded)
        append(',')
        append(csvField(attempt.errorClass ?: ""))
        append(',')
        append(attempt.batteryExempt)
        append(',')
        append(attempt.networkValidated)
    }

    private fun csvField(value: String): String {
        val mustQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!mustQuote) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    fun heartbeatStatusToken(status: HeartbeatStatus): String = when (status) {
        HeartbeatStatus.Ok -> "OK"
        is HeartbeatStatus.Degraded -> "DEGRADED:" + status.reasons.joinToString("+", transform = ::reasonToken)
    }

    private fun reasonToken(reason: DegradedReason): String = when (reason) {
        DegradedReason.MISSING_BLUETOOTH_CONNECT -> "perm-missing"
        DegradedReason.NOT_BATTERY_EXEMPT -> "no-doze-exemption"
        DegradedReason.BLUETOOTH_OFF -> "bt-off"
    }
}
