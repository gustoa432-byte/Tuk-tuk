package com.blink.dtn.net

import com.blink.dtn.db.ContactKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneContactsMatcherTest {

    private val hashA = PhoneE164.sha256Hex("+79991111111")
    private val hashB = PhoneE164.sha256Hex("+79992222222")
    private val hashC = PhoneE164.sha256Hex("+79993333333")

    @Test
    fun twoNumbersSameNodeBecomeOneContact() {
        val numbers = PhoneContactsMatcher.normalizeBook(
            listOf(
                PhoneContactsMatcher.DeviceContact(
                    contactId = 1L,
                    displayName = "Ada",
                    rawNumbers = listOf("+7 999 111-11-11", "8 999 222 22 22")
                )
            )
        )
        val hits = listOf(
            PhoneHit(hash = hashA, exists = true, nodeId = "NODE1", publicKey = "KEY1", username = "ada"),
            PhoneHit(hash = hashB, exists = true, nodeId = "NODE1", publicKey = "KEY1", username = "ada")
        )
        val plan = PhoneContactsMatcher.match(numbers, hits)
        assertEquals(1, plan.inQq.size)
        assertEquals("NODE1", plan.inQq[0].nodeId)
        assertTrue(plan.invite.isEmpty())
        assertFalse(PhoneContactsMatcher.VERIFIED_OUT_OF_BAND)
    }

    @Test
    fun unknownNumbersGoToInviteOncePerContact() {
        val numbers = PhoneContactsMatcher.normalizeBook(
            listOf(
                PhoneContactsMatcher.DeviceContact(
                    contactId = 7L,
                    displayName = "Bob",
                    rawNumbers = listOf("89993333333", "+7 999 333-33-33")
                )
            )
        )
        val hits = listOf(PhoneHit(hash = hashC, exists = false))
        val plan = PhoneContactsMatcher.match(numbers, hits)
        assertTrue(plan.inQq.isEmpty())
        assertEquals(1, plan.invite.size)
        assertEquals("Bob", plan.invite[0].displayName)
    }

    @Test
    fun discoveredTofuIsNotVerified() {
        val tofu = ContactKeyPolicy.merge(
            existingKey = "",
            advertisedKey = "NEW",
            advertisedDerivesToNode = true
        )
        assertEquals(ContactKeyPolicy.Merge.Tofu, tofu)
        assertFalse(PhoneContactsMatcher.VERIFIED_OUT_OF_BAND)
    }

    @Test
    fun keyChangeKeepsOldKey() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "OLD",
            advertisedKey = "NEW",
            advertisedDerivesToNode = true
        )
        assertEquals(ContactKeyPolicy.Merge.KeyChangedKeptOld, outcome)
    }

    @Test
    fun permissionDeniedDoesNotCrash() {
        val denied = PhoneContactsMatcher.afterPermission(false)
        assertEquals(PhoneContactsMatcher.Gate.Denied, denied)
        val gate = PhoneContactsMatcher.gate(explained = true, permissionGranted = false)
        assertEquals(PhoneContactsMatcher.Gate.Denied, gate)
        val plan = PhoneContactsMatcher.match(emptyList(), emptyList())
        assertTrue(plan.inQq.isEmpty())
        assertTrue(plan.invite.isEmpty())
    }

    @Test
    fun gatewayDownMapsToLookupFailedCode() {
        assertEquals("gateway_down", PhoneContactsMatcher.mapLookupFailure(IOException("failed to connect")))
        assertEquals("gateway_down", PhoneContactsMatcher.mapLookupFailure(IllegalStateException("VPS URL not configured")))
        assertEquals("gateway_down", PhoneContactsMatcher.mapLookupFailure(ApiException(503, "unavailable")))
        assertEquals("need_session", PhoneContactsMatcher.mapLookupFailure(ApiException(401, "unauthorized")))
    }

    @Test
    fun chunksRespectBatchCap() {
        val hashes = (0 until 250).map { it.toString().padStart(64, '0') }
        val chunks = PhoneContactsMatcher.chunkHashes(hashes)
        assertEquals(2, chunks.size)
        assertEquals(200, chunks[0].size)
        assertEquals(50, chunks[1].size)
        assertTrue(chunks.all { it.size <= PhoneContactsMatcher.BATCH_SIZE })
    }
}
