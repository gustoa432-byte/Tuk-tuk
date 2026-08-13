package com.blink.dtn.ble

import com.blink.dtn.db.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getQueuedMessages()` only selects status 0/1, so a row parked at
 * PENDING_KEY is invisible to the relay loop until something flips it back.
 * These tests pin that contract for the one shared flush helper.
 */
class PendingKeyFlushTest {

    private class FakeStore(
        val keys: MutableMap<String, String> = mutableMapOf(),
        val pending: MutableMap<String, MutableList<Message>> = mutableMapOf()
    ) : PendingKeyFlush.Store {
        val released = mutableListOf<String>()

        override suspend fun publicKeyFor(peerId: String): String = keys[peerId].orEmpty()

        override suspend fun pendingKeyMessages(peerId: String): List<Message> =
            pending[peerId].orEmpty()

        override suspend fun releasePendingKey(peerId: String) {
            released += peerId
            pending.remove(peerId)
        }

        override suspend fun pendingKeyTargets(): List<String> = pending.keys.toList()
    }

    private fun waiting(id: String, target: String) = Message(
        id = id,
        type = Message.TYPE_PRIVATE,
        senderId = "ME",
        senderNick = "me",
        targetId = target,
        text = "hi",
        timestamp = 1L,
        ttl = 5,
        status = Message.STATUS_PENDING_KEY
    )

    @Test
    fun withoutAKeyNothingIsReleased() = runBlocking {
        val store = FakeStore(pending = mutableMapOf("PEER" to mutableListOf(waiting("m1", "PEER"))))
        val enqueued = mutableListOf<Message>()

        val ids = PendingKeyFlush.flushPeer(store, "PEER") { enqueued += it }

        assertTrue(ids.isEmpty())
        assertTrue(enqueued.isEmpty())
        assertTrue("row must stay parked", store.released.isEmpty())
    }

    @Test
    fun keyArrivalFlipsStatusAndRequeues() = runBlocking {
        val store = FakeStore(
            keys = mutableMapOf("PEER" to "PUBKEY"),
            pending = mutableMapOf(
                "PEER" to mutableListOf(waiting("m1", "PEER"), waiting("m2", "PEER"))
            )
        )
        val enqueued = mutableListOf<Message>()

        val ids = PendingKeyFlush.flushPeer(store, "PEER") { enqueued += it }

        assertEquals(listOf("m1", "m2"), ids)
        assertEquals(listOf("PEER"), store.released)
        assertEquals(2, enqueued.size)
        assertTrue(
            "requeued copies must be visible to getQueuedMessages()",
            enqueued.all { it.status == Message.STATUS_PENDING }
        )
    }

    @Test
    fun flushIsIdempotent() = runBlocking {
        val store = FakeStore(
            keys = mutableMapOf("PEER" to "PUBKEY"),
            pending = mutableMapOf("PEER" to mutableListOf(waiting("m1", "PEER")))
        )
        PendingKeyFlush.flushPeer(store, "PEER") {}
        val second = PendingKeyFlush.flushPeer(store, "PEER") {}
        assertTrue(second.isEmpty())
    }

    @Test
    fun blankPeerIsIgnored() = runBlocking {
        val store = FakeStore()
        assertTrue(PendingKeyFlush.flushPeer(store, "  ".trim()) {}.isEmpty())
    }

    @Test
    fun maintenanceSweepReleasesOnlyPeersWhoseKeyIsKnown() = runBlocking {
        val store = FakeStore(
            keys = mutableMapOf("KNOWN" to "PUBKEY"),
            pending = mutableMapOf(
                "KNOWN" to mutableListOf(waiting("m1", "KNOWN")),
                "UNKNOWN" to mutableListOf(waiting("m2", "UNKNOWN"))
            )
        )
        val enqueued = mutableListOf<Message>()

        val ids = PendingKeyFlush.flushAllKnownKeys(store) { enqueued += it }

        assertEquals(listOf("m1"), ids)
        assertEquals(listOf("KNOWN"), store.released)
        assertEquals(listOf("m1"), enqueued.map { it.id })
    }
}
