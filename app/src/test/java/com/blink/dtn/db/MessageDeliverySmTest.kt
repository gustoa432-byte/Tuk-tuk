package com.blink.dtn.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeliverySmTest {

    @Test
    fun ackIsSticky() {
        assertFalse(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_DELIVERED_ACK,
                Message.STATUS_PENDING
            )
        )
        assertEquals(
            Message.STATUS_DELIVERED_ACK,
            MessageDeliverySm.applyAuto(Message.STATUS_DELIVERED_ACK, Message.STATUS_FAILED)
        )
    }

    @Test
    fun storedInNeighborDoesNotRegressToPending() {
        assertFalse(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_STORED_IN_NEIGHBOR,
                Message.STATUS_PENDING
            )
        )
        assertTrue(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_STORED_IN_NEIGHBOR,
                Message.STATUS_DELIVERED_ACK
            )
        )
    }

    @Test
    fun pendingCanAdvanceToStored() {
        assertTrue(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_PENDING,
                Message.STATUS_STORED_IN_NEIGHBOR
            )
        )
        assertTrue(MessageDeliverySm.isE2eDelivered(Message.STATUS_DELIVERED_ACK))
        assertFalse(MessageDeliverySm.isE2eDelivered(Message.STATUS_STORED_IN_NEIGHBOR))
    }
}
