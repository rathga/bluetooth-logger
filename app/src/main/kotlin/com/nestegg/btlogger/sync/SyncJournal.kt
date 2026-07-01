package com.nestegg.btlogger.sync

import android.content.Context
import java.io.File

/** Append-only JSONL journal of sync attempts, capped at [SYNC_JOURNAL_CAP] records. */
class SyncJournal internal constructor(private val baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    fun append(attempt: SyncAttempt) {
        val line = (attempt.toJsonLine() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(WRITE_LOCK) {
            baseDir.mkdirs()
            val file = journalFile()
            file.appendBytes(line)
            rotate(file)
        }
    }

    fun retainedAttempts(): List<SyncAttempt> {
        val file = journalFile()
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parseSyncAttemptOrNull)
    }

    private fun rotate(file: File) {
        val lines = file.readLines()
        val kept = retainWithinCap(lines, SYNC_JOURNAL_CAP)
        if (kept.size == lines.size) return
        file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun journalFile(): File = File(baseDir, FILE_NAME)

    companion object {
        internal const val FILE_NAME = "sync-journal.jsonl"
        private val WRITE_LOCK = Any()
    }
}
