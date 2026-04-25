package com.nestegg.btlogger.storage

import android.content.Context
import java.io.File

/**
 * Append-only JSONL store of Bluetooth events in app-private storage.
 * One file per month: events-YYYY-MM.jsonl
 */
class EventStore(private val context: Context) {

    fun append(event: BtEvent) {
        // TODO: append a JSONL line to the current month's file
    }

    fun unsynced(sinceByteOffset: Long): List<BtEvent> {
        // TODO: read events appended after sinceByteOffset
        return emptyList()
    }

    private fun monthFile(yearMonth: String): File =
        File(context.filesDir, "events-$yearMonth.jsonl")
}
