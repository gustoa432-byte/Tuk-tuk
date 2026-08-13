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

    // ── Custody rounds ───────────────────────────────────────────────────────

    @Test
    fun custodyRequeueIsTheOnlyWayBackToTheQueue() {
        assertEquals(
            Message.STATUS_PENDING,
            MessageDeliverySm.applyCustodyRequeue(Message.STATUS_STORED_IN_NEIGHBOR)
        )
        assertEquals(
            Message.STATUS_PENDING,
            MessageDeliverySm.applyCustodyRequeue(Message.STATUS_IN_FLIGHT)
        )
        // The automatic pipeline still cannot do it on its own.
        assertFalse(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_STORED_IN_NEIGHBOR,
                Message.STATUS_PENDING
            )
        )
    }

    @Test
    fun custodyRequeueNeverDowngradesADeliveredParcel() {
        assertFalse(MessageDeliverySm.mayCustodyRequeue(Message.STATUS_DELIVERED_ACK))
        assertEquals(
            Message.STATUS_DELIVERED_ACK,
            MessageDeliverySm.applyCustodyRequeue(Message.STATUS_DELIVERED_ACK)
        )
        assertEquals(
            Message.STATUS_EXPIRED,
            MessageDeliverySm.applyCustodyRequeue(Message.STATUS_EXPIRED)
        )
    }

    @Test
    fun expiryIsHonestAndDistinctFromFailure() {
        assertEquals(
            Message.STATUS_EXPIRED,
            MessageDeliverySm.applyExpiry(Message.STATUS_STORED_IN_NEIGHBOR)
        )
        assertEquals(
            Message.STATUS_EXPIRED,
            MessageDeliverySm.applyExpiry(Message.STATUS_FAILED)
        )
        assertEquals(
            "an ACK already happened — expiry must not overwrite it",
            Message.STATUS_DELIVERED_ACK,
            MessageDeliverySm.applyExpiry(Message.STATUS_DELIVERED_ACK)
        )
        assertTrue(MessageDeliverySm.isTerminal(Message.STATUS_EXPIRED))
        assertFalse(MessageDeliverySm.isTerminal(Message.STATUS_FAILED))
    }

    @Test
    fun aLateAckStillWinsAfterExpiry() {
        assertTrue(
            MessageDeliverySm.mayAutoUpdate(
                Message.STATUS_EXPIRED,
                Message.STATUS_DELIVERED_ACK
            )
        )
        assertFalse(
            MessageDeliverySm.mayAutoUpdate(Message.STATUS_EXPIRED, Message.STATUS_PENDING)
        )
        assertFalse(
            MessageDeliverySm.mayAutoUpdate(Message.STATUS_EXPIRED, Message.STATUS_FAILED)
        )
    }
}
