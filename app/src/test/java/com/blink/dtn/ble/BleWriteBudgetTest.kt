package com.blink.dtn.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleWriteBudgetTest {

    @Test
    fun capsAtAndroidAttrMaxEvenIfMtuHuge() {
        val budget = BleWriteBudget()
        assertEquals(512, budget.maxWriteBytes("AA:BB", negotiatedMtu = 517))
        assertEquals(20, budget.maxWriteBytes("AA:BB", negotiatedMtu = 23))
    }

    @Test
    fun downshiftOnOversizedThenEncodeMtuRespectsCap() {
        val budget = BleWriteBudget()
        val applied = budget.noteOversizedWrite("AA:BB", attemptedBytes = 400)
        assertEquals(256, applied)
        assertEquals(256, budget.maxWriteBytes("AA:BB", negotiatedMtu = 517))
        assertEquals(259, budget.encodeMtu("AA:BB", 517))
        assertTrue(budget.exceedsBudget("AA:BB", 517, 300))
        assertFalse(budget.exceedsBudget("AA:BB", 517, 200))
    }

    @Test
    fun chunkEncodeRespectsAttrCeiling() {
        val payload = ByteArray(2000) { 1 }
        val chunks = BleChunkCodec.encode(payload, mtu = 515, messageId = 42)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(
                "chunk ${chunk.size} exceeds ANDROID_MAX_ATTR_BYTES",
                chunk.size <= BleWriteBudget.ANDROID_MAX_ATTR_BYTES
            )
        }
    }

    @Test
    fun detectsOversizedErrorMessages() {
        val budget = BleWriteBudget()
        assertTrue(budget.isOversizedError("value longer than max length of an attribute"))
        assertTrue(budget.isOversizedError("Attribute value too long"))
        assertFalse(budget.isOversizedError("GATT_ERROR"))
    }
}
