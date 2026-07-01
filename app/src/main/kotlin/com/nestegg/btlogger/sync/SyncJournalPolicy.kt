package com.nestegg.btlogger.sync

/** How many past sync attempts the on-device journal (and its Drive copy) retains. */
const val SYNC_JOURNAL_CAP = 200

/**
 * The retention decision for the append-only sync journal: keep the newest [cap]
 * lines, dropping the oldest. Operating on raw lines keeps it agnostic of the
 * record schema and cheap to apply after every append.
 */
fun retainWithinCap(lines: List<String>, cap: Int): List<String> =
    if (lines.size <= cap) lines else lines.takeLast(cap)
