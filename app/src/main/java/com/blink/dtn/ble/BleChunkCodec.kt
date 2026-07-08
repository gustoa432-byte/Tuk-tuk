package com.blink.dtn.ble

import java.util.UUID

data class BleChunk(
    val messageId: Int,
    val index: Int,
    val total: Int,
    val payload: ByteArray
)

object BleChunkCodec {
    private const val MAGIC = 0xAB.toByte()
    private const val HEADER_SIZE = 7
    private const val MAX_CHUNKS = 255

    fun newChunkMessageId(): Int = UUID.randomUUID().hashCode()

    fun encode(payload: ByteArray, mtu: Int, messageId: Int): List<ByteArray> {
        val safeChunkSize = safeChunkSize(mtu)
        val chunks = payload.toList().chunked(safeChunkSize)
        require(chunks.size <= MAX_CHUNKS) {
            "Payload requires ${chunks.size} BLE chunks, max supported is $MAX_CHUNKS"
        }

        return chunks.mapIndexed { index, chunkList ->
            header(messageId, index, chunks.size) + chunkList.toByteArray()
        }
    }

    fun decode(value: ByteArray): BleChunk? {
        if (value.size < HEADER_SIZE || value[0] != MAGIC) return null

        val b0 = value[1].toInt() and 0xFF
        val b1 = value[2].toInt() and 0xFF
        val b2 = value[3].toInt() and 0xFF
        val b3 = value[4].toInt() and 0xFF
        val messageId = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        val index = value[5].toInt() and 0xFF
        val total = value[6].toInt() and 0xFF

        if (total == 0 || index >= total) return null
        return BleChunk(messageId, index, total, value.copyOfRange(HEADER_SIZE, value.size))
    }

    private fun safeChunkSize(mtu: Int): Int = (mtu - 10).coerceAtLeast(10)

    private fun header(messageId: Int, index: Int, total: Int): ByteArray {
        return byteArrayOf(
            MAGIC,
            (messageId shr 24).toByte(),
            (messageId shr 16).toByte(),
            (messageId shr 8).toByte(),
            messageId.toByte(),
            index.toByte(),
            total.toByte()
        )
    }
}
