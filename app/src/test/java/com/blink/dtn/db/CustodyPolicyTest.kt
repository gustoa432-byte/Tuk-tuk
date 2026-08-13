package com.blink.dtn.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The central semantic rule: handing a parcel to a neighbour is not delivery.
 * Everything here is about *when* a PRIVATE message comes back to the queue and
 * when it honestly dies.
 */
class CustodyPolicyTest {

    private val now = 1_700_000_000_000L

    private fun decide(
        status: Int,
        type: String = Message.TYPE_PRIVATE,
        createdAt: Long = now,
        custodySince: Long = now,
        rounds: Int = 0,
        isAck: Boolean = false
    ) = CustodyPolicy.decide(
        status = status,
        type = type,
        isAck = isAck,
        createdAt = createdAt,
        custodySince = custodySince,
        custodyRounds = rounds,
        now = now
    )

    @Test
    fun freshNeighborCustodyIsHeld() {
        val decision = decide(
            Message.STATUS_STORED_IN_NEIGHBOR,
            custodySince = now - CustodyPolicy.CUSTODY_WINDOW_MS + 1_000
        )
        assertTrue(decision is CustodyPolicy.Decision.Hold)
    }

    @Test
    fun expiredCustodyWindowGoesBackToTheQueue() {
        val decision = decide(
            Message.STATUS_STORED_IN_NEIGHBOR,
            custodySince = now - CustodyPolicy.CUSTODY_WINDOW_MS
        )
        assertTrue(decision is CustodyPolicy.Decision.Requeue)
        assertEquals(1, (decision as CustodyPolicy.Decision.Requeue).nextRound)
    }

    @Test
    fun custodyRoundsAreBounded() {
        val decision = decide(
            Message.STATUS_STORED_IN_NEIGHBOR,
            custodySince = now - 10 * CustodyPolicy.CUSTODY_WINDOW_MS,
            rounds = CustodyPolicy.MAX_CUSTODY_ROUNDS
        )
        assertTrue("must stop re-flooding after the bound", decision is CustodyPolicy.Decision.Hold)
    }

    @Test
    fun ageLimitBeatsEverythingButDelivery() {
        val old = now - CustodyPolicy.MAX_AGE_MS
        assertTrue(
            decide(Message.STATUS_STORED_IN_NEIGHBOR, createdAt = old) is
                CustodyPolicy.Decision.Expire
        )
        assertTrue(
            decide(Message.STATUS_PENDING, createdAt = old) is CustodyPolicy.Decision.Expire
        )
        assertTrue(
            "an ACKed parcel is never re-touched",
            decide(Message.STATUS_DELIVERED_ACK, createdAt = old) is CustodyPolicy.Decision.Hold
        )
    }

    @Test
    fun staleInFlightIsRecoveredWithoutBurningACustodyRound() {
        val decision = decide(
            Message.STATUS_IN_FLIGHT,
            custodySince = now - CustodyPolicy.IN_FLIGHT_STALE_MS
        )
        assertTrue(decision is CustodyPolicy.Decision.Requeue)
        // A lost TxResult is our bug, not a courier failure — same round.
        assertEquals(0, (decision as CustodyPolicy.Decision.Requeue).nextRound)
        assertEquals("in_flight_stale", decision.reason)
    }

    @Test
    fun inFlightInsideTheWatchdogWindowIsLeftAlone() {
        val decision = decide(
            Message.STATUS_IN_FLIGHT,
            custodySince = now - CustodyPolicy.IN_FLIGHT_STALE_MS / 2
        )
        assertTrue(decision is CustodyPolicy.Decision.Hold)
    }

    @Test
    fun publicAndAckRowsHaveNoCustody() {
        val stale = now - 10 * CustodyPolicy.CUSTODY_WINDOW_MS
        assertTrue(
            "PUBLIC is fire-and-forget: nothing acks it",
            decide(
                Message.STATUS_STORED_IN_NEIGHBOR,
                type = "PUBLIC",
                custodySince = stale
            ) is CustodyPolicy.Decision.Hold
        )
        assertTrue(
            decide(
                Message.STATUS_STORED_IN_NEIGHBOR,
                custodySince = stale,
                isAck = true
            ) is CustodyPolicy.Decision.Hold
        )
    }

    @Test
    fun legacyRowsWithoutCustodyStampFallBackToCreationTime() {
        val decision = decide(
            Message.STATUS_STORED_IN_NEIGHBOR,
            createdAt = now - CustodyPolicy.CUSTODY_WINDOW_MS - 1,
            custodySince = 0L
        )
        assertTrue(decision is CustodyPolicy.Decision.Requeue)
    }
}
