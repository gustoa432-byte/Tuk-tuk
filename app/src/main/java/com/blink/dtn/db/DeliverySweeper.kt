package com.blink.dtn.db

import android.util.Log

/**
 * Periodic reconciliation between Room (the single source of truth) and the
 * volatile transport state.
 *
 * Handles three losses the transports cannot recover on their own:
 *  1. custody timeout — a neighbour took the parcel but no end-to-end ACK came
 *     back within [CustodyPolicy.CUSTODY_WINDOW_MS] → another round;
 *  2. lost outcome — an IN_FLIGHT row whose TX batch is gone (process death,
 *     cancelled coroutine, dropped result) → back to the queue;
 *  3. honest expiry — anything of ours past [CustodyPolicy.MAX_AGE_MS] becomes
 *     [Message.STATUS_EXPIRED], which is *not* the same as "failed to send".
 */
object DeliverySweeper {
    private const val TAG = "DeliverySweep"

    data class Result(
        val requeued: Int = 0,
        val expired: Int = 0
    ) {
        val changed: Boolean get() = requeued > 0 || expired > 0
    }

    suspend fun sweep(
        dao: BLinkDao,
        myNodeId: String,
        now: Long = System.currentTimeMillis()
    ): Result {
        var requeued = 0
        var expired = 0

        for (msg in dao.getAgedOutOwnMessages(myNodeId, now - CustodyPolicy.MAX_AGE_MS)) {
            val next = MessageDeliverySm.applyExpiry(msg.status)
            if (next != msg.status) {
                dao.updateMessageStatus(msg.id, next)
                expired++
                com.blink.dtn.telemetry.TraceStore.finish(
                    msg.id,
                    "Expired",
                    com.blink.dtn.telemetry.detailsOf(
                        "ageMs" to (now - msg.timestamp),
                        "custodyRounds" to msg.custodyRounds
                    )
                )
            }
        }

        for (msg in dao.getCustodyCandidates(myNodeId)) {
            val decision = CustodyPolicy.decide(
                status = msg.status,
                type = msg.type,
                isAck = msg.isAck,
                createdAt = msg.timestamp,
                custodySince = msg.custodySince,
                custodyRounds = msg.custodyRounds,
                now = now
            )
            when (decision) {
                is CustodyPolicy.Decision.Hold -> Unit
                is CustodyPolicy.Decision.Expire -> {
                    val next = MessageDeliverySm.applyExpiry(msg.status)
                    if (next != msg.status) {
                        dao.updateMessageStatus(msg.id, next)
                        expired++
                    }
                }
                is CustodyPolicy.Decision.Requeue -> {
                    val next = MessageDeliverySm.applyCustodyRequeue(msg.status)
                    if (next == Message.STATUS_PENDING) {
                        // Clearing is_bridge_synced lets the gateway carry it again too.
                        dao.updateMessageCustodyRequeue(msg.id, next, decision.nextRound)
                        requeued++
                        com.blink.dtn.telemetry.TraceStore.stage(
                            msg.id,
                            "Custody.Requeue",
                            com.blink.dtn.telemetry.detailsOf(
                                "reason" to decision.reason,
                                "round" to decision.nextRound,
                                "waitedMs" to (now - maxOf(msg.custodySince, 0L).let {
                                    if (it > 0L) it else msg.timestamp
                                })
                            ),
                            visual = "🔁 Новый круг доставки"
                        )
                    }
                }
            }
        }

        if (requeued > 0 || expired > 0) {
            Log.i(TAG, "custody sweep: requeued=$requeued expired=$expired")
        }
        return Result(requeued = requeued, expired = expired)
    }
}
