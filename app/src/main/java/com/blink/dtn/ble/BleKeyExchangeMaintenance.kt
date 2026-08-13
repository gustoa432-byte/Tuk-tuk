package com.blink.dtn.ble

import android.util.Log
import com.blink.dtn.db.BLinkDao
import com.blink.dtn.db.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Periodically re-issues IDENTITY_REQUEST for peers we still lack a public key for.
 */
internal class BleKeyExchangeMaintenance(
    private val dao: BLinkDao,
    private val myNodeId: String,
    private val scopeProvider: () -> CoroutineScope,
    intervalMs: Long = 20_000L,
    private val deps: Deps
) {
    interface Deps {
        fun currentNick(): String
        fun defaultTtl(): Int
        fun enqueueMessage(msg: Message)
    }

    private val requestBackoff = ConcurrentHashMap<String, Long>()
    private val store = PendingKeyFlush.store(dao)
    private var job: Job? = null

    @Volatile
    var intervalMs: Long = intervalMs
        private set

    fun setIntervalMs(ms: Long) {
        intervalMs = ms.coerceAtLeast(5_000L)
    }

    fun start() {
        job?.cancel()
        job = scopeProvider().launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e("DTN", "Key request retry loop error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        requestBackoff.clear()
    }

    private suspend fun tick() {
        // Keys can arrive by routes that never touch mesh ingress (QR, VPS
        // directory, /contacts/add). Release anything already unblocked first —
        // same helper the IDENTITY ingress path uses.
        PendingKeyFlush.flushAllKnownKeys(store) { deps.enqueueMessage(it) }

        val targets = dao.getPendingKeyTargets()
        val now = System.currentTimeMillis()
        val interval = intervalMs
        for (targetId in targets) {
            val profile = dao.getProfileById(targetId)
            if (profile != null && profile.publicKey.isNotEmpty()) continue

            val lastRequest = requestBackoff[targetId] ?: 0L
            if (now - lastRequest < interval) continue
            requestBackoff[targetId] = now

            deps.enqueueMessage(
                Message(
                    id = com.blink.dtn.utils.MeshIdGenerator.next(myNodeId),
                    type = "IDENTITY_REQUEST",
                    senderId = myNodeId,
                    senderNick = deps.currentNick(),
                    targetId = targetId,
                    text = "",
                    room = "system",
                    timestamp = System.currentTimeMillis(),
                    ttl = deps.defaultTtl()
                )
            )
        }
    }
}
