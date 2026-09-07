package com.nestegg.btlogger.sync

import com.nestegg.btlogger.storage.BtEvent
import com.nestegg.btlogger.storage.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvFormatTest {

    @Test fun `every event type spells itself into the event_type column`() {
        assertEquals(
            "these are the event_type spellings the second column of " +
                "bluetooth-log-<deviceTag>-YYYY-MM.csv carries; before updating them, ask whether a " +
                "stored value changed and what the reconciler already has on disk",
            listOf("CONNECTED", "DISCONNECTED", "HEARTBEAT"),
            EventType.entries.map { eventTypeColumnOf(it) },
        )
    }

    private fun eventTypeColumnOf(eventType: EventType): String =
        CsvFormat.row(BtEvent(0L, eventType, null, "")).split(",")[1]
}
