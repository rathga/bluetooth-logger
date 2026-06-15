package com.nestegg.btlogger.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {

    @Test fun `no issues when granted and exempt`() {
        val status = SetupStatus(bluetoothConnectGranted = true, batteryExempt = true)
        assertTrue(status.isHealthy)
        assertEquals(emptyList<SetupIssue>(), status.issues)
    }

    @Test fun `missing bluetooth connect is reported`() {
        val status = SetupStatus(bluetoothConnectGranted = false, batteryExempt = true)
        assertFalse(status.isHealthy)
        assertEquals(listOf(SetupIssue.MISSING_BLUETOOTH_CONNECT), status.issues)
    }

    @Test fun `missing battery exemption is reported`() {
        val status = SetupStatus(bluetoothConnectGranted = true, batteryExempt = false)
        assertFalse(status.isHealthy)
        assertEquals(listOf(SetupIssue.NOT_BATTERY_EXEMPT), status.issues)
    }

    @Test fun `both issues reported in order`() {
        val status = SetupStatus(bluetoothConnectGranted = false, batteryExempt = false)
        assertFalse(status.isHealthy)
        assertEquals(
            listOf(SetupIssue.MISSING_BLUETOOTH_CONNECT, SetupIssue.NOT_BATTERY_EXEMPT),
            status.issues,
        )
    }
}
