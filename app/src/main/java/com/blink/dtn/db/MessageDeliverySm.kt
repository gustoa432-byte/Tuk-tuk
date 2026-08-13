package com.blink.dtn.db

/**
 * Delivery status state machine for outbound PRIVATE (and similar) messages.
 *
 * Honest UX rule: only [Message.STATUS_DELIVERED_ACK] is end-to-end "delivered".
 * [Message.STATUS_STORED_IN_NEIGHBOR] means GATT/VPS hop to a neighbor — not ACK.
 */
object MessageDeliverySm {

    /** Never regress past these once reached (except explicit user retry). */
    fun isStickySuccess(status: Int): Boolean =
        status == Message.STATUS_DELIVERED_ACK ||
            status == Message.STATUS_STORED_IN_NEIGHBOR

    fun isE2eDelivered(status: Int): Boolean =
        status == Message.STATUS_DELIVERED_ACK

    fun isRetryable(status: Int): Boolean =
        status == Message.STATUS_FAILED ||
            status == Message.STATUS_PENDING ||
            status == Message.STATUS_PENDING_KEY ||
            status == Message.STATUS_IN_FLIGHT

    /**
     * Whether automatic pipeline may move [current] → [next].
     * User-driven resend may force PENDING via [forceUserRetry].
     */
    fun mayAutoUpdate(current: Int, next: Int): Boolean {
        if (current == next) return true
        if (current == Message.STATUS_DELIVERED_ACK) {
            return next == Message.STATUS_DELIVERED_ACK
        }
        // Once at a neighbor, don't drop back to queue/in-flight on soft TX noise.
        if (current == Message.STATUS_STORED_IN_NEIGHBOR) {
            return next == Message.STATUS_STORED_IN_NEIGHBOR ||
                next == Message.STATUS_DELIVERED_ACK
        }
        return when (next) {
            Message.STATUS_PENDING,
            Message.STATUS_PENDING_KEY,
            Message.STATUS_IN_FLIGHT,
            Message.STATUS_STORED_IN_NEIGHBOR,
            Message.STATUS_DELIVERED_ACK,
            Message.STATUS_FAILED -> true
            else -> false
        }
    }

    fun applyAuto(current: Int, next: Int): Int =
        if (mayAutoUpdate(current, next)) next else current
}
