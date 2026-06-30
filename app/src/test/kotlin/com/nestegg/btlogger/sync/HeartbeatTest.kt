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
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - (HEARTBEAT_INTERVAL_MILLIS - 1)
        assertFalse(shouldEmitHeartbeat(now, last))
    }

    @Test fun `emits exactly at the interval boundary`() {
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - HEARTBEAT_INTERVAL_MILLIS
        assertTrue(shouldEmitHeartbeat(now, last))
    }

    @Test fun `emits well past the interval`() {
        val now = 100 * HEARTBEAT_INTERVAL_MILLIS
        val last = now - (3 * HEARTBEAT_INTERVAL_MILLIS)
        assertTrue(shouldEmitHeartbeat(now, last))
    }

    @Test fun `status is OK when all preconditions are healthy`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals("OK", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing permission`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertEquals("DEGRADED:perm-missing", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a missing doze exemption`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertEquals("DEGRADED:no-doze-exemption", heartbeatStatus(setup, bluetoothAdapterEnabled = true))
    }

    @Test fun `status flags a disabled adapter`() {
        val setup = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertEquals("DEGRADED:bt-off", heartbeatStatus(setup, bluetoothAdapterEnabled = false))
    }

    @Test fun `status lists every failing precondition in a fixed order`() {
        val setup = SetupStatus(bluetoothConnectGranted = false, batteryExempt = false)
        assertEquals(
            "DEGRADED:perm-missing+no-doze-exemption+bt-off",
            heartbeatStatus(setup, bluetoothAdapterEnabled = false),
        )
    }
}
