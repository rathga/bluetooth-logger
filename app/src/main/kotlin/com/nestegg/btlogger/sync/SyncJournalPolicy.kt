package com.nestegg.btlogger.sync

const val SYNC_JOURNAL_CAP = 200

/** Keeps the newest [cap] lines, dropping the oldest. */
fun retainWithinCap(lines: List<String>, cap: Int): List<String> =
    if (lines.size <= cap) lines else lines.takeLast(cap)
