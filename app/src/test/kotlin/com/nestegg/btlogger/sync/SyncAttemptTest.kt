package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncAttemptTest {

    private fun attempt(
        utcTimestamp: Long = 1_700_000_000_000,
        trigger: SyncTrigger = SyncTrigger.PERIODIC,
        outcome: SyncOutcome = SyncOutcome.SUCCESS,
        rowsUploaded: Int = 0,
        errorClass: String? = null,
        batteryExempt: Boolean = false,
        networkValidated: Boolean = false,
    ) = SyncAttempt(utcTimestamp, trigger, outcome, rowsUploaded, errorClass, batteryExempt, networkValidated)

    private fun attemptJsonWithOutcome(outcome: String): String =
        attempt(outcome = SyncOutcome.SUCCESS).toJsonLine()
            .replace("\"outcome\":\"success\"", "\"outcome\":\"$outcome\"")

    @Test fun `round-trips every field through JSON`() {
        val attempt = attempt(
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
        val attempt = attempt(
            trigger = SyncTrigger.PERIODIC,
            outcome = SyncOutcome.NO_EVENTS,
            errorClass = null,
            networkValidated = true,
        )
        val parsed = parseSyncAttemptOrNull(attempt.toJsonLine())
        assertEquals(attempt, parsed)
        assertNull(parsed?.errorClass)
    }

    @Test fun `round-trips every outcome`() {
        for (outcome in SyncOutcome.entries) {
            val attempt = attempt(outcome = outcome)
            assertEquals(outcome, parseSyncAttemptOrNull(attempt.toJsonLine())?.outcome)
        }
    }

    @Test fun `an error class with quotes survives`() {
        val attempt = attempt(
            trigger = SyncTrigger.MANUAL,
            outcome = SyncOutcome.ERROR,
            errorClass = "a \"weird\" name",
            batteryExempt = true,
            networkValidated = true,
        )
        assertEquals(attempt, parseSyncAttemptOrNull(attempt.toJsonLine()))
    }

    @Test fun `garbage lines parse to null`() {
        assertNull(parseSyncAttemptOrNull("not json"))
        assertNull(parseSyncAttemptOrNull("{\"ts\":1}"))
    }

    @Test fun `a line whose outcome is unknown fails the parse`() {
        assertNull(parseSyncAttemptOrNull(attemptJsonWithOutcome("bogus")))
    }

    @Test fun `every wire name maps back to its outcome`() {
        for (outcome in SyncOutcome.entries) {
            assertEquals(outcome, SyncOutcome.fromWireName(outcome.wireName))
        }
    }

    @Test fun `an unknown outcome wire name maps to null`() {
        assertNull(SyncOutcome.fromWireName("bogus"))
        assertNull(SyncOutcome.fromWireName(null))
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
