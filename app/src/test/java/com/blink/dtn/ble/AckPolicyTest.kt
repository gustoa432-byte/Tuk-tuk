package com.blink.dtn.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckPolicyTest {
    @Test
    fun deliveryAck_requiresMatchingTarget() {
        assertTrue(AckPolicy.acceptDeliveryAck("PEER_A", "PEER_A"))
        assertFalse(AckPolicy.acceptDeliveryAck("PEER_A", "ATTACKER"))
        assertTrue(AckPolicy.acceptDeliveryAck(null, "ANY"))
        assertTrue(AckPolicy.acceptDeliveryAck("", "ANY"))
    }

    @Test
    fun privateDeliveryAck_neverAcceptsATargetlessRow() {
        // PRIVATE always has a destination; a blank one means the ACK cannot be
        // proven to come from it, so "delivered" must not be claimed.
        assertFalse(AckPolicy.acceptDeliveryAck(null, "ANY", requireTarget = true))
        assertFalse(AckPolicy.acceptDeliveryAck("   ", "ANY", requireTarget = true))
        assertTrue(AckPolicy.acceptDeliveryAck("PEER_A", "PEER_A", requireTarget = true))
        assertFalse(AckPolicy.acceptDeliveryAck("PEER_A", "ATTACKER", requireTarget = true))
    }

    @Test
    fun backpackWipe_onlyFromDestination() {
        assertTrue(AckPolicy.acceptBackpackWipe("DEST", "DEST"))
        assertFalse(AckPolicy.acceptBackpackWipe("DEST", "OTHER"))
        assertFalse(AckPolicy.acceptBackpackWipe(null, "DEST"))
    }
}
