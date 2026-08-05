package com.blink.dtn.ble

/**
 * Pure ACK acceptance rules (unit-tested). Delivery forgery is blocked when the
 * original message had a target and the ACK sender is not that target.
 */
object AckPolicy {
    fun acceptDeliveryAck(originalTargetId: String?, ackSenderId: String): Boolean {
        val expected = originalTargetId?.trim().orEmpty()
        if (expected.isEmpty()) return true
        return expected == ackSenderId.trim()
    }

    /** Relay may drop backpack only when ACK comes from the message's destination. */
    fun acceptBackpackWipe(heldTargetId: String?, ackSenderId: String): Boolean {
        val dest = heldTargetId?.trim().orEmpty()
        if (dest.isEmpty()) return false
        return dest == ackSenderId.trim()
    }
}
