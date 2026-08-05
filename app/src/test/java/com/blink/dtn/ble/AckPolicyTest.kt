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
    fun backpackWipe_onlyFromDestination() {
        assertTrue(AckPolicy.acceptBackpackWipe("DEST", "DEST"))
        assertFalse(AckPolicy.acceptBackpackWipe("DEST", "OTHER"))
        assertFalse(AckPolicy.acceptBackpackWipe(null, "DEST"))
    }
}
