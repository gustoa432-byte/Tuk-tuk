package com.blink.dtn.db

/**
 * Custody policy for outbound parcels — the single place where "how long do we
 * trust a neighbour before trying again" is defined.
 *
 * Core rule: **handing a parcel to a neighbour is not delivery.** A PRIVATE
 * message stays alive in the system until either an end-to-end ACK from its
 * target ([Message.STATUS_DELIVERED_ACK]) or an honest age expiry
 * ([Message.STATUS_EXPIRED]).
 *
 * Chosen constants:
 *  - [CUSTODY_WINDOW_MS] = 10 min. Long enough for a realistic multi-hop DTN
 *    delivery + ACK round trip (neighbour must physically move), short enough
 *    that a dead-end courier does not silently eat the message for hours.
 *  - [MAX_CUSTODY_ROUNDS] = 3. Bounds re-flood amplification: worst case one
 *    message is broadcast 4 times over 30 minutes (initial + 3 rounds), after
 *    which we stop spending radio and just wait for an ACK or the age limit.
 *  - [MAX_AGE_MS] = 48h — unchanged hard age limit, already enforced by the
 *    relay loop and the mesh ingress dedup journal.
 *  - [IN_FLIGHT_STALE_MS] = 90s — 2× the 45s TX batch watchdog, so a row can
 *    only be swept back to the queue once the batch can no longer resolve it.
 *
 * Interaction with de-dup (`seen_packets`): a re-sent parcel keeps its stable
 * id, so a neighbour that already saw it drops it as a duplicate. That is
 * intentional — the point of a custody round is to reach *other* couriers
 * (the peer set changes as people move), not to re-deliver to the same one.
 * The mesh de-dup journal is pruned at 48h, so no message can be resurrected
 * after its own age limit either.
 */
object CustodyPolicy {

    /** How long a neighbour may hold a parcel before we try another round. */
    const val CUSTODY_WINDOW_MS = 10L * 60L * 1000L

    /** Maximum re-send rounds after the first handover. */
    const val MAX_CUSTODY_ROUNDS = 3

    /** Hard age limit for any parcel in the system. */
    const val MAX_AGE_MS = 48L * 60L * 60L * 1000L

    /** IN_FLIGHT rows older than this lost their TX batch (watchdog is 45s). */
    const val IN_FLIGHT_STALE_MS = 90L * 1000L

    sealed class Decision {
        /** Nothing to do — still inside the custody window or already terminal. */
        object Hold : Decision()

        /** Put the parcel back into the send queue for another round. */
        data class Requeue(val nextRound: Int, val reason: String) : Decision()

        /** Honest end of life: aged out without an end-to-end ACK. */
        object Expire : Decision()
    }

    /**
     * Pure decision for one of *our own* outbound rows.
     *
     * @param custodySince ms of the current attempt / handover, 0 when unknown
     *        (legacy rows) — then [createdAt] is used as the reference point.
     */
    fun decide(
        status: Int,
        type: String,
        isAck: Boolean,
        createdAt: Long,
        custodySince: Long,
        custodyRounds: Int,
        now: Long
    ): Decision {
        if (status == Message.STATUS_DELIVERED_ACK || status == Message.STATUS_EXPIRED) {
            return Decision.Hold
        }
        if (now - createdAt >= MAX_AGE_MS) return Decision.Expire
        if (isAck) return Decision.Hold

        val since = if (custodySince > 0L) custodySince else createdAt
        val waited = now - since

        if (status == Message.STATUS_IN_FLIGHT) {
            // Lost TxResult / process death: nothing can resolve this row anymore.
            return if (waited >= IN_FLIGHT_STALE_MS) {
                Decision.Requeue(custodyRounds, "in_flight_stale")
            } else {
                Decision.Hold
            }
        }

        if (status != Message.STATUS_STORED_IN_NEIGHBOR) return Decision.Hold
        // Only PRIVATE has an end-to-end ACK to wait for; PUBLIC is fire-and-forget.
        if (type != Message.TYPE_PRIVATE) return Decision.Hold
        if (custodyRounds >= MAX_CUSTODY_ROUNDS) return Decision.Hold
        if (waited < CUSTODY_WINDOW_MS) return Decision.Hold
        return Decision.Requeue(custodyRounds + 1, "custody_timeout")
    }
}
