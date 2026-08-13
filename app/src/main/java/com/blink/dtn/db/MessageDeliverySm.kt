package com.blink.dtn.db

/**
 * Delivery status state machine for outbound PRIVATE (and similar) messages.
 *
 * Honest UX rule: only [Message.STATUS_DELIVERED_ACK] is end-to-end "delivered".
 * [Message.STATUS_STORED_IN_NEIGHBOR] means GATT/VPS hop to a neighbor — not ACK.
 *
 * Neighbour custody is sticky against *soft TX noise* but not against time:
 * [CustodyPolicy] may take a parcel back out of custody through
 * [applyCustodyRequeue], which is the only sanctioned way back to the queue.
 */
object MessageDeliverySm {

    /** Never regress past these once reached (except explicit user retry / custody round). */
    fun isStickySuccess(status: Int): Boolean =
        status == Message.STATUS_DELIVERED_ACK ||
            status == Message.STATUS_STORED_IN_NEIGHBOR

    fun isE2eDelivered(status: Int): Boolean =
        status == Message.STATUS_DELIVERED_ACK

    /** No automatic pipeline step may leave these (a late ACK still may). */
    fun isTerminal(status: Int): Boolean =
        status == Message.STATUS_DELIVERED_ACK || status == Message.STATUS_EXPIRED

    fun isRetryable(status: Int): Boolean =
        status == Message.STATUS_FAILED ||
            status == Message.STATUS_PENDING ||
            status == Message.STATUS_PENDING_KEY ||
            status == Message.STATUS_IN_FLIGHT

    /**
     * Whether automatic pipeline may move [current] → [next].
     * User-driven resend may force PENDING; the custody sweep uses
     * [applyCustodyRequeue] / [applyExpiry] instead of this gate.
     */
    fun mayAutoUpdate(current: Int, next: Int): Boolean {
        if (current == next) return true
        if (current == Message.STATUS_DELIVERED_ACK) {
            return next == Message.STATUS_DELIVERED_ACK
        }
        // A late ACK is still the truth after an honest expiry; nothing else is.
        if (current == Message.STATUS_EXPIRED) {
            return next == Message.STATUS_DELIVERED_ACK
        }
        // Once at a neighbor, don't drop back to queue/in-flight on soft TX noise.
        if (current == Message.STATUS_STORED_IN_NEIGHBOR) {
            return next == Message.STATUS_STORED_IN_NEIGHBOR ||
                next == Message.STATUS_DELIVERED_ACK ||
                next == Message.STATUS_EXPIRED
        }
        return when (next) {
            Message.STATUS_PENDING,
            Message.STATUS_PENDING_KEY,
            Message.STATUS_IN_FLIGHT,
            Message.STATUS_STORED_IN_NEIGHBOR,
            Message.STATUS_DELIVERED_ACK,
            Message.STATUS_EXPIRED,
            Message.STATUS_FAILED -> true
            else -> false
        }
    }

    fun applyAuto(current: Int, next: Int): Int =
        if (mayAutoUpdate(current, next)) next else current

    /**
     * Controlled custody transition: a parcel whose custody window elapsed goes
     * back to the send queue. Deliberately narrow — it must never re-open the
     * door to a [Message.STATUS_DELIVERED_ACK] downgrade.
     */
    fun mayCustodyRequeue(current: Int): Boolean =
        current == Message.STATUS_STORED_IN_NEIGHBOR ||
            current == Message.STATUS_IN_FLIGHT

    fun applyCustodyRequeue(current: Int): Int =
        if (mayCustodyRequeue(current)) Message.STATUS_PENDING else current

    /** Honest age expiry — allowed from anything that is not already delivered. */
    fun mayExpire(current: Int): Boolean =
        current != Message.STATUS_DELIVERED_ACK && current != Message.STATUS_EXPIRED

    fun applyExpiry(current: Int): Int =
        if (mayExpire(current)) Message.STATUS_EXPIRED else current
}
