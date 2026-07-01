package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncJournalPolicyTest {

    @Test fun `keeps every line when under the cap`() {
        val lines = (1..5).map { "line-$it" }
        assertEquals(lines, retainWithinCap(lines, cap = 10))
    }

    @Test fun `keeps every line at exactly the cap`() {
        val lines = (1..10).map { "line-$it" }
        assertEquals(lines, retainWithinCap(lines, cap = 10))
    }

    @Test fun `drops the oldest lines above the cap`() {
        val lines = (1..12).map { "line-$it" }
        assertEquals((3..12).map { "line-$it" }, retainWithinCap(lines, cap = 10))
    }
}
