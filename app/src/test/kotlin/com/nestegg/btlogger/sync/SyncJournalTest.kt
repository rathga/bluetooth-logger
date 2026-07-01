package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun journal(): SyncJournal = SyncJournal(tmp.root)

    private fun attemptAt(ts: Long, outcome: SyncOutcome = SyncOutcome.SUCCESS): SyncAttempt =
        SyncAttempt(ts, SyncTrigger.PERIODIC, outcome, rowsUploaded = 1, errorClass = null,
            batteryExempt = true, networkValidated = true)

    @Test fun `reads back appended attempts oldest first`() {
        val j = journal()
        j.append(attemptAt(1L, SyncOutcome.NO_ACCOUNT))
        j.append(attemptAt(2L, SyncOutcome.SUCCESS))

        val attempts = j.retainedAttempts()
        assertEquals(listOf(1L, 2L), attempts.map { it.utcTimestamp })
        assertEquals(SyncOutcome.NO_ACCOUNT, attempts.first().outcome)
    }

    @Test fun `an empty journal reads as no attempts`() {
        assertTrue(journal().retainedAttempts().isEmpty())
    }

    @Test fun `rotation caps the journal at the newest entries`() {
        val j = journal()
        val total = SYNC_JOURNAL_CAP + 25
        for (ts in 1..total) j.append(attemptAt(ts.toLong()))

        val attempts = j.retainedAttempts()
        assertEquals(SYNC_JOURNAL_CAP, attempts.size)
        assertEquals(26L, attempts.first().utcTimestamp)
        assertEquals(total.toLong(), attempts.last().utcTimestamp)
    }
}
