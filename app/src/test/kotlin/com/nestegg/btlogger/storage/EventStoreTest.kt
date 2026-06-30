package com.nestegg.btlogger.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(): EventStore = EventStore(tmp.root)

    @Test fun `append writes one JSONL line per event into the correct month file`() {
        val s = store()
        val ts = utc(2026, 4, 25, 10, 0)
        s.append(BtEvent(ts, EventType.CONNECTED, "Tesla", "AA:BB:CC:DD:EE:FF"))
        s.append(BtEvent(ts + 60_000, EventType.DISCONNECTED, "Tesla", "AA:BB:CC:DD:EE:FF"))

        val file = File(tmp.root, "events-2026-04.jsonl")
        assertTrue(file.exists())
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"type\":\"CONNECTED\""))
        assertTrue(lines[1].contains("\"type\":\"DISCONNECTED\""))
    }

    @Test fun `events bucket into months based on UTC timestamp`() {
        val s = store()
        s.append(BtEvent(utc(2026, 3, 31, 23, 30), EventType.CONNECTED, "X", "00:00:00:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 1, 0, 30), EventType.CONNECTED, "X", "00:00:00:00:00:01"))

        assertEquals(listOf("2026-03", "2026-04"), s.months())
    }

    @Test fun `unsynced returns events appended since the offset and advances offset to file end`() {
        val s = store()
        val ym = "2026-04"
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        val first = s.unsynced(ym, 0L)
        assertEquals(1, first.events.size)

        s.append(BtEvent(utc(2026, 4, 25, 10, 0), EventType.DISCONNECTED, "Car", "AA:BB:CC:00:00:01"))
        val second = s.unsynced(ym, first.newByteOffset)
        assertEquals(1, second.events.size)
        assertEquals(EventType.DISCONNECTED, second.events.single().eventType)

        val third = s.unsynced(ym, second.newByteOffset)
        assertTrue(third.events.isEmpty())
        assertEquals(second.newByteOffset, third.newByteOffset)
    }

    @Test fun `unsynced ignores a trailing partial line until it is completed`() {
        val s = store()
        val ym = "2026-04"
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))

        val file = File(tmp.root, "events-$ym.jsonl")
        file.appendText("{\"ts\":12345,\"type\":\"CONNECTED\",\"name\":\"partial")
        val partialOffsetStart = file.length() - "{\"ts\":12345,\"type\":\"CONNECTED\",\"name\":\"partial".length

        val chunk = s.unsynced(ym, 0L)
        assertEquals(1, chunk.events.size)
        assertEquals(partialOffsetStart, chunk.newByteOffset)

        file.appendText("\",\"mac\":\"00:00:00:00:00:99\"}\n")
        val rest = s.unsynced(ym, chunk.newByteOffset)
        assertEquals(1, rest.events.size)
        assertEquals("partial", rest.events.single().deviceName)
    }

    @Test fun `unsynced on a missing month file returns empty`() {
        val s = store()
        val chunk = s.unsynced("2099-01", 0L)
        assertTrue(chunk.events.isEmpty())
        assertEquals(0L, chunk.newByteOffset)
    }

    @Test fun `device names with quotes and newlines round-trip`() {
        val tricky = "Richard's \"car\"\nphone"
        val event = BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, tricky, "AA:BB:CC:00:00:01")
        val line = event.toJsonLine()
        assertEquals(event, parseJsonLineOrNull(line))
    }

    @Test fun `recent returns the newest events first across months`() {
        val s = store()
        s.append(BtEvent(utc(2026, 3, 31, 23, 0), EventType.CONNECTED, "Old", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 1, 0, 0), EventType.CONNECTED, "New1", "AA:BB:CC:00:00:02"))
        s.append(BtEvent(utc(2026, 4, 1, 0, 1), EventType.DISCONNECTED, "New2", "AA:BB:CC:00:00:03"))

        val recent = s.recent(2)
        assertEquals(listOf("New2", "New1"), recent.map { it.deviceName })
    }

    @Test fun `recent reaches into earlier months when current month is short`() {
        val s = store()
        s.append(BtEvent(utc(2026, 3, 31, 23, 0), EventType.CONNECTED, "Old", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 1, 0, 0), EventType.CONNECTED, "New", "AA:BB:CC:00:00:02"))

        val recent = s.recent(5)
        assertEquals(listOf("New", "Old"), recent.map { it.deviceName })
    }

    @Test fun `null device name round-trips`() {
        val event = BtEvent(utc(2026, 4, 25, 9, 0), EventType.DISCONNECTED, null, "AA:BB:CC:00:00:02")
        val parsed = parseJsonLineOrNull(event.toJsonLine())
        assertEquals(event, parsed)
        assertNull(parsed?.deviceName)
    }

    @Test fun `lastRecordMillis is null for an empty store`() {
        assertNull(store().lastRecordMillis())
    }

    @Test fun `lastRecordMillis returns the newest timestamp including heartbeats`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        val beat = utc(2026, 4, 26, 9, 0)
        s.append(BtEvent(beat, EventType.HEARTBEAT, "OK", ""))
        assertEquals(beat, s.lastRecordMillis())
    }

    @Test fun `recentConnections excludes heartbeat records`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 25, 10, 0), EventType.HEARTBEAT, "OK", ""))
        s.append(BtEvent(utc(2026, 4, 25, 11, 0), EventType.DISCONNECTED, "Car", "AA:BB:CC:00:00:01"))

        val recent = s.recentConnections(10)
        assertEquals(listOf(EventType.DISCONNECTED, EventType.CONNECTED), recent.map { it.eventType })
    }

    @Test fun `lastHeartbeat returns the most recent heartbeat`() {
        val s = store()
        s.append(BtEvent(utc(2026, 4, 25, 9, 0), EventType.HEARTBEAT, "DEGRADED:bt-off", ""))
        s.append(BtEvent(utc(2026, 4, 26, 9, 0), EventType.CONNECTED, "Car", "AA:BB:CC:00:00:01"))
        s.append(BtEvent(utc(2026, 4, 27, 9, 0), EventType.HEARTBEAT, "OK", ""))

        val beat = s.lastHeartbeat()
        assertEquals("OK", beat?.deviceName)
        assertEquals(utc(2026, 4, 27, 9, 0), beat?.utcTimestamp)
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }
}
