package com.blink.dtn.ble

import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message

/**
 * One code path for "the recipient's public key arrived — release everything
 * that was waiting for it".
 *
 * Both the mesh ingress (IDENTITY_ANNOUNCEMENT) and the periodic
 * [BleKeyExchangeMaintenance] tick go through here, so a key learned by *any*
 * route (QR, VPS directory, /contacts/add, mesh identity) unblocks the queue.
 *
 * Why the status flip matters: `getQueuedMessages()` only selects status 0/1,
 * so a row left at [Message.STATUS_PENDING_KEY] is invisible to the relay loop
 * forever — re-enqueueing alone was not enough.
 */
internal object PendingKeyFlush {
    private const val TAG = "PendingKeyFlush"

    /** Narrow store surface so the policy is unit-testable without Room. */
    interface Store {
        suspend fun publicKeyFor(peerId: String): String
        suspend fun pendingKeyMessages(peerId: String): List<Message>
        suspend fun releasePendingKey(peerId: String)
        suspend fun pendingKeyTargets(): List<String>
    }

    fun store(dao: BLinkDao): Store = object : Store {
        override suspend fun publicKeyFor(peerId: String): String =
            dao.getProfileById(peerId)?.publicKey.orEmpty()

        override suspend fun pendingKeyMessages(peerId: String): List<Message> =
            dao.getMessagesPendingKeyForUser(peerId)

        override suspend fun releasePendingKey(peerId: String) =
            dao.releasePendingKeyMessages(peerId)

        override suspend fun pendingKeyTargets(): List<String> =
            dao.getPendingKeyTargets()
    }

    /**
     * @return ids released back into the send queue (empty when we still have no key).
     */
    suspend fun flushPeer(
        store: Store,
        peerId: String,
        enqueue: (Message) -> Unit
    ): List<String> {
        if (peerId.isBlank()) return emptyList()
        if (store.publicKeyFor(peerId).isBlank()) return emptyList()
        val waiting = store.pendingKeyMessages(peerId)
        if (waiting.isEmpty()) return emptyList()
        // Flip 4 → 0 first: the relay loop can then pick the rows up on its own
        // even if the enqueue path below is interrupted (process death).
        store.releasePendingKey(peerId)
        for (msg in waiting) {
            enqueue(msg.copy(status = Message.STATUS_PENDING))
        }
        Log.i(TAG, "released ${waiting.size} message(s) waiting for key of $peerId")
        return waiting.map { it.id }
    }

    /** Sweep every peer that still has PENDING_KEY rows but whose key we now have. */
    suspend fun flushAllKnownKeys(
        store: Store,
        enqueue: (Message) -> Unit
    ): List<String> {
        val released = mutableListOf<String>()
        for (peerId in store.pendingKeyTargets()) {
            released += flushPeer(store, peerId, enqueue)
        }
        return released
    }
}
