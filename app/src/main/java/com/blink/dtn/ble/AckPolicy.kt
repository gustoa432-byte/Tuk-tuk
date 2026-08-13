package com.blink.dtn.ble

/**
 * Pure ACK acceptance rules (unit-tested). Delivery forgery is blocked when the
 * original message had a target and the ACK sender is not that target.
 */
object AckPolicy {
    /**
     * @param requireTarget true for PRIVATE: an end-to-end "delivered" claim is
     *        only meaningful when the acked row actually has a destination, so a
     *        missing/blank target must never be treated as "anyone may confirm".
     *        PUBLIC keeps the permissive rule — its ACK only ever yields
     *        neighbour custody, never DELIVERED_ACK.
     */
    fun acceptDeliveryAck(
        originalTargetId: String?,
        ackSenderId: String,
        requireTarget: Boolean = false
    ): Boolean {
        val expected = originalTargetId?.trim().orEmpty()
        if (expected.isEmpty()) return !requireTarget
        return expected == ackSenderId.trim()
    }

    /** Relay may drop backpack only when ACK comes from the message's destination. */
    fun acceptBackpackWipe(heldTargetId: String?, ackSenderId: String): Boolean {
        val dest = heldTargetId?.trim().orEmpty()
        if (dest.isEmpty()) return false
        return dest == ackSenderId.trim()
    }
}
