package com.nestegg.btlogger.sync

import com.nestegg.btlogger.storage.BtEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object CsvFormat {

    const val HEADER = "utc_iso,event_type,device_name,device_mac"

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

    private fun csvField(value: String): String {
        val mustQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!mustQuote) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
