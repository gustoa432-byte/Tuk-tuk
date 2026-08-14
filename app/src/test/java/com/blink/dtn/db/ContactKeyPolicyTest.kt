package com.blink.dtn.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactKeyPolicyTest {

    @Test
    fun emptyLocalKeyIsTofu() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "",
            advertisedKey = "NEW",
            advertisedDerivesToNode = true
        )
        assertEquals(ContactKeyPolicy.Merge.Tofu, outcome)
    }

    @Test
    fun sameKeyIsUnchanged() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "SAME",
            advertisedKey = "SAME",
            advertisedDerivesToNode = true
        )
        assertEquals(ContactKeyPolicy.Merge.Unchanged, outcome)
    }

    @Test
    fun differentKeyIsNeverReplaced() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "OLD",
            advertisedKey = "NEW",
            advertisedDerivesToNode = true
        )
        assertEquals(ContactKeyPolicy.Merge.KeyChangedKeptOld, outcome)
    }

    @Test
    fun unboundKeyIsRejected() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "",
            advertisedKey = "SPOOF",
            advertisedDerivesToNode = false
        )
        assertEquals(ContactKeyPolicy.Merge.Rejected, outcome)
    }

    @Test
    fun blankAdvertisedLeavesExisting() {
        val outcome = ContactKeyPolicy.merge(
            existingKey = "OLD",
            advertisedKey = "",
            advertisedDerivesToNode = false
        )
        assertEquals(ContactKeyPolicy.Merge.Unchanged, outcome)
    }
}
