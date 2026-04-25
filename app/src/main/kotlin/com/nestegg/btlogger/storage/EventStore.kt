package com.nestegg.btlogger.storage

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class EventChunk(
    val events: List<BtEvent>,
    val newByteOffset: Long,
)

/**
 * Append-only JSONL store of Bluetooth events in app-private storage.
 * One file per month: events-YYYY-MM.jsonl, where YYYY-MM is the UTC year-month
 * of the event's timestamp.
 */
class EventStore internal constructor(private val baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    fun append(event: BtEvent) {
        val file = monthFile(yearMonthOf(event.utcTimestamp))
        val bytes = (event.toJsonLine() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(WRITE_LOCK) {
            file.parentFile?.mkdirs()
            file.appendBytes(bytes)
        }
    }

    /** YYYY-MM keys for every month file currently present, ascending. */
    fun months(): List<String> =
        baseDir.listFiles()
            ?.mapNotNull { FILE_REGEX.matchEntire(it.name)?.groupValues?.get(1) }
            ?.sorted()
            .orEmpty()

    /** Total event lines across every month file. Reads each file once. */
    fun totalEvents(): Int =
        months().sumOf { yearMonth ->
            monthFile(yearMonth).useLines { it.count() }
        }

    /**
     * Most recent [limit] events, newest first. Reads month files from newest to
     * oldest until the cap is reached, so a normal call only touches the current
     * month's file.
     */
    fun recent(limit: Int): List<BtEvent> {
        if (limit <= 0) return emptyList()
        val out = ArrayDeque<BtEvent>(limit)
        for (yearMonth in months().reversed()) {
            val lines = monthFile(yearMonth).readLines()
            for (i in lines.indices.reversed()) {
                parseJsonLineOrNull(lines[i])?.let(out::addLast)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }

    /**
     * Read complete event lines from the given month file starting at sinceByteOffset.
     * A trailing partial line (writer crashed mid-write) is left for next time —
     * newByteOffset advances only past the last complete `\n`-terminated line.
     */
    fun unsynced(yearMonth: String, sinceByteOffset: Long): EventChunk {
        val file = monthFile(yearMonth)
        if (!file.exists()) return EventChunk(emptyList(), 0L)
        val length = file.length()
        if (sinceByteOffset >= length) return EventChunk(emptyList(), length)

        val events = mutableListOf<BtEvent>()
        var lastTerminatedAt = sinceByteOffset
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(sinceByteOffset)
            val buf = ByteArray((length - sinceByteOffset).toInt())
            raf.readFully(buf)
            var lineStart = 0
            for (i in buf.indices) {
                if (buf[i] == NEWLINE) {
                    val line = String(buf, lineStart, i - lineStart, Charsets.UTF_8)
                    parseJsonLineOrNull(line)?.let(events::add)
                    lineStart = i + 1
                    lastTerminatedAt = sinceByteOffset + lineStart
                }
            }
        }
        return EventChunk(events, lastTerminatedAt)
    }

    private fun monthFile(yearMonth: String): File =
        File(baseDir, "events-$yearMonth.jsonl")

    companion object {
        private val WRITE_LOCK = Any()
        private val FILE_REGEX = Regex("""events-(\d{4}-\d{2})\.jsonl""")
        private const val NEWLINE: Byte = 0x0A

        fun yearMonthOf(utcMillis: Long): String =
            SimpleDateFormat("yyyy-MM", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(utcMillis))
    }
}
