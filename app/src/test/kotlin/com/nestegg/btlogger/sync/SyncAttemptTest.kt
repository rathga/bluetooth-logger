package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncAttemptTest {

    @Test fun `round-trips every field through JSON`() {
        val attempt = SyncAttempt(
            utcTimestamp = 1_700_000_000_000,
            trigger = SyncTrigger.MANUAL,
            outcome = SyncOutcome.SUCCESS,
            rowsUploaded = 42,
            errorClass = "IOException",
            batteryExempt = true,
            networkValidated = false,
        )
        assertEquals(attempt, parseSyncAttemptOrNull(attempt.toJsonLine()))
    }

    @Test fun `round-trips a null error class`() {
        val attempt = SyncAttempt(
            utcTimestamp = 1_700_000_000_000,
            trigger = SyncTrigger.PERIODIC,
            outcome = SyncOutcome.NO_EVENTS,
            rowsUploaded = 0,
            errorClass = null,
            batteryExempt = false,
            networkValidated = true,
        )
        val parsed = parseSyncAttemptOrNull(attempt.toJsonLine())
        assertEquals(attempt, parsed)
        assertNull(parsed?.errorClass)
    }

    @Test fun `round-trips every outcome`() {
        for (outcome in SyncOutcome.entries) {
            val attempt = SyncAttempt(1L, SyncTrigger.PERIODIC, outcome, 0, null, false, false)
            assertEquals(outcome, parseSyncAttemptOrNull(attempt.toJsonLine())?.outcome)
        }
    }

    @Test fun `an error class with quotes survives`() {
        val attempt = SyncAttempt(1L, SyncTrigger.MANUAL, SyncOutcome.ERROR, 0, "a \"weird\" name", true, true)
        assertEquals(attempt, parseSyncAttemptOrNull(attempt.toJsonLine()))
    }

    @Test fun `garbage lines parse to null`() {
        assertNull(parseSyncAttemptOrNull("not json"))
        assertNull(parseSyncAttemptOrNull("{\"ts\":1}"))
    }

    @Test fun `unknown outcome fails the parse`() {
        assertNull(
            parseSyncAttemptOrNull(
                "{\"ts\":1,\"trigger\":\"manual\",\"outcome\":\"bogus\"," +
                    "\"rows\":0,\"error\":null,\"battery_exempt\":true,\"network_validated\":true}",
            ),
        )
    }

    @Test fun `unknown trigger falls back to periodic`() {
        assertEquals(SyncTrigger.PERIODIC, SyncTrigger.fromWireName("nonsense"))
        assertEquals(SyncTrigger.PERIODIC, SyncTrigger.fromWireName(null))
        assertEquals(SyncTrigger.MANUAL, SyncTrigger.fromWireName("manual"))
    }

    @Test fun `only success and no-events count as clean`() {
        val clean = SyncOutcome.entries.filter { it.isClean }.toSet()
        assertEquals(setOf(SyncOutcome.SUCCESS, SyncOutcome.NO_EVENTS), clean)
    }
}
