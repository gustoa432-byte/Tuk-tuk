package com.blink.dtn.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BleChunkReassemblerTest {
    private fun reassembler() = BleChunkReassembler(
        scopeProvider = { CoroutineScope(Dispatchers.Unconfined) },
        maxConcurrentBuffers = 4,
        bufferTtlMs = 60_000L
    )

    @Test
    fun pinsTotalFromFirstChunkAndRejectsMismatch() {
        val r = reassembler()
        val payload = ByteArray(40) { it.toByte() }
        val chunks = BleChunkCodec.encode(payload, mtu = 30, messageId = 42)
        assertTrue(chunks.size >= 2)

        val first = chunks[0]
        val second = chunks[1].copyOf()
        second[6] = 99.toByte() // lie about total

        assertNull(r.ingest(first))
        assertNull(r.ingest(second))
    }

    @Test
    fun assemblesWhenTotalsAgree() {
        val r = reassembler()
        val payload = ByteArray(40) { it.toByte() }
        val chunks = BleChunkCodec.encode(payload, mtu = 30, messageId = 7)
        var assembled: ByteArray? = null
        for (c in chunks) {
            assembled = r.ingest(c) ?: assembled
        }
        assertNotNull(assembled)
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
