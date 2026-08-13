package com.blink.dtn.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactQrBareIdTest {

    @Test
    fun looksLikeNodeId_base32Length16() {
        assertTrue(NodeIdentity.looksLikeNodeId("ABCDEFG234567ABC"))
        assertFalse(NodeIdentity.looksLikeNodeId("short"))
        assertFalse(NodeIdentity.looksLikeNodeId("ABCDEFG234567AB1")) // '1' not in base32
    }

    @Test
    fun parseBareNodeId() {
        val id = "ABCDEFG234567ABC"
        val ok = ContactQr.parse(id, myNodeId = "ZZZZZZZZZZZZZZZZ") as ContactQr.Result.Ok
        assertEquals(id, ok.parsed.nodeId)
        assertFalse(ok.parsed.hasPinnedKey)
    }

    @Test
    fun parseRejectsSelfBareId() {
        val id = "ABCDEFG234567ABC"
        assertEquals(ContactQr.Result.Self, ContactQr.parse(id, myNodeId = id))
    }

    @Test
    fun parseRejectsGarbage() {
        assertEquals(ContactQr.Result.NotContact, ContactQr.parse("hello", myNodeId = "X"))
        assertEquals(
            ContactQr.Result.NotContact,
            ContactQr.parse("not-a-contact-qr", myNodeId = "X", allowBareNodeId = false)
        )
    }
}
