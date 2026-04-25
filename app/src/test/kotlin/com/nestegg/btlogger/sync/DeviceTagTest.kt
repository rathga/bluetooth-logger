package com.nestegg.btlogger.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTagTest {

    @Test fun `combines lowercased model with first 8 chars of android id`() {
        assertEquals(
            "sm-g981b-a1b2c3d4",
            DeviceTag.build(model = "SM-G981B", androidId = "a1b2c3d4e5f6g7h8"),
        )
    }

    @Test fun `non-alphanumerics in model collapse to single dashes`() {
        assertEquals(
            "pixel-7-pro-12345678",
            DeviceTag.build(model = "Pixel  7 / Pro!", androidId = "12345678abcdef00"),
        )
    }

    @Test fun `falls back to placeholders when inputs are missing`() {
        assertEquals("device-unknown", DeviceTag.build(model = null, androidId = null))
        assertEquals("device-unknown", DeviceTag.build(model = "", androidId = ""))
    }

    @Test fun `model is truncated to 16 chars`() {
        val tag = DeviceTag.build(model = "VeryVeryLongDeviceModelName", androidId = "abcdef0123456789")
        val modelPart = tag.substringBeforeLast("-abcdef01")
        assertEquals(16, modelPart.length)
    }
}
