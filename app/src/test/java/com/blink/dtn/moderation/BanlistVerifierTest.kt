package com.blink.dtn.moderation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BanlistVerifierTest {
    @Test
    fun acceptsValidSignature() {
        val secret = "tuktuk-banlist-v1"
        val nodes = listOf("AAA", "BBB")
        val exp = System.currentTimeMillis() / 1000L + 3600
        val payload = nodes.joinToString("|") + "|" + exp
        val sig = BanlistVerifier.hmacSha256Hex(secret, payload)
        assertTrue(BanlistVerifier.verify(nodes, exp, sig, secret))
    }

    @Test
    fun rejectsTamperAndExpiry() {
        val secret = "tuktuk-banlist-v1"
        val nodes = listOf("AAA")
        val exp = System.currentTimeMillis() / 1000L + 3600
        val sig = BanlistVerifier.hmacSha256Hex(secret, "AAA|$exp")
        assertFalse(BanlistVerifier.verify(listOf("EVIL"), exp, sig, secret))
        assertFalse(BanlistVerifier.verify(nodes, exp - 10_000, sig, secret))
    }
}
