package com.blink.dtn.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRadioBackoffTest {
    @Test
    fun scanDelayGrowsThenCaps() {
        val d0 = BleRadioBackoff.scanDelayMs(0, jitter = 0)
        val d3 = BleRadioBackoff.scanDelayMs(3, jitter = 0)
        val d9 = BleRadioBackoff.scanDelayMs(9, jitter = 0)
        assertEquals(BleRadioBackoff.SCAN_BASE_MS, d0)
        assertTrue(d3 > d0)
        assertEquals(BleRadioBackoff.SCAN_MAX_MS, d9)
    }

    @Test
    fun scanRetryCap() {
        assertTrue(BleRadioBackoff.shouldRetryScan(0))
        assertTrue(BleRadioBackoff.shouldRetryScan(BleRadioBackoff.SCAN_MAX_ATTEMPTS - 1))
        assertFalse(BleRadioBackoff.shouldRetryScan(BleRadioBackoff.SCAN_MAX_ATTEMPTS))
    }

    @Test
    fun gatt133WaitsLongerThanGeneric() {
        val generic = BleRadioBackoff.gattDelayMs(0, status = 0, jitter = 0)
        val s133 = BleRadioBackoff.gattDelayMs(0, status = BleRadioBackoff.GATT_133, jitter = 0)
        assertTrue(s133 > generic)
    }
}
