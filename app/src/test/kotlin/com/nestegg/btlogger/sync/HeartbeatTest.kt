package com.nestegg.btlogger.sync

import com.nestegg.btlogger.setup.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {

    private val oneDayMillis: Long = 24L * 60 * 60 * 1000

    @Test fun `emits when there is no prior record`() {
        assertTrue(shouldEmitHeartbeat(nowMillis = 1_000_000, lastRecordMillis = null))
    }

    @Test fun `does not emit before a day has elapsed`() {
        assertFalse(emittedAfter(elapsed = oneDayMillis - 1))
    }

    @Test fun `emits exactly at the one-day boundary`() {
        assertTrue(emittedAfter(elapsed = oneDayMillis))
    }

    @Test fun `emits well past a day`() {
        assertTrue(emittedAfter(elapsed = 3 * oneDayMillis))
    }

    @Test fun `status is OK when all preconditions are healthy`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals(HeartbeatStatus.Ok, statusOf(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing permission`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.MISSING_BLUETOOTH_CONNECT)),
            statusOf(setup, bluetoothAdapterEnabled = true),
        )
    }

    @Test fun `status flags a missing doze exemption`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.NOT_BATTERY_EXEMPT)),
            statusOf(setup, bluetoothAdapterEnabled = true),
        )
    }

    @Test fun `status flags a disabled adapter`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.BLUETOOTH_OFF)),
            statusOf(setup, bluetoothAdapterEnabled = false),
        )
    }

    @Test fun `status flags capture preconditions that could not be read`() {
        assertEquals(
            HeartbeatStatus.Degraded(listOf(DegradedReason.DEVICE_STATE_UNREADABLE)),
            heartbeatStatus(CapturePreconditions.Unreadable),
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
            statusOf(setup, bluetoothAdapterEnabled = false),
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
            "these are the DEGRADED: token spellings the device_name column of " +
                "bluetooth-log-<deviceTag>-YYYY-MM.csv carries; before updating them, ask whether a " +
                "stored value changed and what the reconciler already has on disk",
            "DEGRADED:state-unreadable+perm-missing+no-doze-exemption+bt-off",
            CsvFormat.heartbeatStatusToken(HeartbeatStatus.Degraded(DegradedReason.entries)),
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

    private fun statusOf(setup: SetupStatus, bluetoothAdapterEnabled: Boolean): HeartbeatStatus =
        heartbeatStatus(CapturePreconditions.Measured(setup, bluetoothAdapterEnabled))

    private fun emittedAfter(elapsed: Long): Boolean {
        val now = 30L * oneDayMillis
        return shouldEmitHeartbeat(now, lastRecordMillis = now - elapsed)
    }
}
