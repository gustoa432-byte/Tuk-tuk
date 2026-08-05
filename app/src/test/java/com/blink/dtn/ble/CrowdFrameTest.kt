package com.blink.dtn.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrowdFrameTest {
    @Test
    fun encodeDecode_roundTrip() {
        val raw = CrowdFrame.encode(
            kind = CrowdFrame.KIND_PUBLIC,
            text = "hello stadium",
            idHash = 0x12345678,
            room = 42,
            ttl = 3
        )
        assertTrue(CrowdFrame.looksLike(raw))
        val d = CrowdFrame.decode(raw)
        assertNotNull(d)
        assertEquals(CrowdFrame.KIND_PUBLIC, d!!.kind)
        assertEquals(3, d.ttl)
        assertEquals(0x12345678, d.idHash)
        assertEquals(42, d.room)
        assertEquals("hello stadium", d.text)
    }

    @Test
    fun looksLike_rejectsNoise() {
        assertFalse(CrowdFrame.looksLike(byteArrayOf(1, 2, 3)))
        assertFalse(CrowdFrame.looksLike("not-tk".toByteArray()))
    }

    @Test
    fun text_truncatedToMax() {
        val long = "x".repeat(200)
        val raw = CrowdFrame.encode(CrowdFrame.KIND_SOS, long, 1)
        val d = CrowdFrame.decode(raw)!!
        assertTrue(d.text.toByteArray(Charsets.UTF_8).size <= CrowdFrame.MAX_TEXT)
    }
}
