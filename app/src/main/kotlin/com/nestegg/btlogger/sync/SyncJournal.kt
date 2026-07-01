package com.nestegg.btlogger.sync

import android.content.Context
import java.io.File

/**
 * Append-only JSONL journal of sync attempts in app-private storage, capped at
 * [SYNC_JOURNAL_CAP] records so it stays small. Survives process death and reboot,
 * and is the source for both the on-device history and the Drive diagnostics copy.
 */
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

    /** All retained attempts, oldest first. */
    fun recentAttempts(): List<SyncAttempt> {
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
