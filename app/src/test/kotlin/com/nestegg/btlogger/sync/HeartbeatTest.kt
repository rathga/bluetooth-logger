package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {

    @Test fun `emits when there is no prior record`() {
        assertTrue(shouldEmitHeartbeat(nowMillis = 1_000_000, lastRecordMillis = null))
    }

    @Test fun `does not emit before the interval has elapsed`() {
        assertFalse(emittedAfter(elapsed = HEARTBEAT_INTERVAL_MILLIS - 1))
    }

    @Test fun `emits exactly at the interval boundary`() {
        assertTrue(emittedAfter(elapsed = HEARTBEAT_INTERVAL_MILLIS))
    }

    @Test fun `emits well past the interval`() {
        assertTrue(emittedAfter(elapsed = 3 * HEARTBEAT_INTERVAL_MILLIS))
    }

    @Test fun `status is OK when all preconditions are healthy`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals(HeartbeatStatus.Ok, heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing permission`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.MISSING_BLUETOOTH_CONNECT)),
            heartbeatStatus(setup, bluetoothAdapterEnabled = true),
        )
    }

    @Test fun `status flags a missing doze exemption`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.NOT_BATTERY_EXEMPT)),
            heartbeatStatus(setup, bluetoothAdapterEnabled = true),
        )
    }

    @Test fun `status flags a disabled adapter`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.BLUETOOTH_OFF)),
            heartbeatStatus(setup, bluetoothAdapterEnabled = false),
        )
    }

    @Test fun `status lists every failing precondition in a fixed order`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = false)
        assertEquals(
            HeartbeatStatus.Degraded(
                listOf(
                    DegradedReason.MISSING_BLUETOOTH_CONNECT,
                    DegradedReason.NOT_BATTERY_EXEMPT,
                    DegradedReason.BLUETOOTH_OFF,
                ),
            ),
            heartbeatStatus(setup, bluetoothAdapterEnabled = false),
        )
    }

    @Test fun `renders the healthy token`() {
        assertEquals("OK", CsvFormat.heartbeatStatusToken(HeartbeatStatus.Ok))
    }

    @Test fun `renders a single degraded token`() {
        assertEquals(
            "DEGRADED:perm-missing",
            CsvFormat.heartbeatStatusToken(HeartbeatStatus.Degraded(listOf(DegradedReason.MISSING_BLUETOOTH_CONNECT))),
        )
    }

    @Test fun `renders every degraded token joined in order`() {
        assertEquals(
            "DEGRADED:perm-missing+no-doze-exemption+bt-off",
            CsvFormat.heartbeatStatusToken(
                HeartbeatStatus.Degraded(
                    listOf(
                        DegradedReason.MISSING_BLUETOOTH_CONNECT,
                        DegradedReason.NOT_BATTERY_EXEMPT,
                        DegradedReason.BLUETOOTH_OFF,
                    ),
                ),
            ),
        )
    }

    @Test fun `renders non-adjacent reasons joined by a single separator`() {
        assertEquals(
            "DEGRADED:perm-missing+bt-off",
            CsvFormat.heartbeatStatusToken(
                HeartbeatStatus.Degraded(listOf(DegradedReason.MISSING_BLUETOOTH_CONNECT, DegradedReason.BLUETOOTH_OFF)),
            ),
        )
    }

    private fun emittedAfter(elapsed: Long): Boolean {
        val now = 30L * HEARTBEAT_INTERVAL_MILLIS
        return shouldEmitHeartbeat(now, lastRecordMillis = now - elapsed)
    }
}
