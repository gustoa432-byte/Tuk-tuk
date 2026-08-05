package com.blink.dtn.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRelayPolicyTest {
    @Test
    fun identityTypes_detected() {
        assertTrue(IdentityRelayPolicy.isIdentityType("IDENTITY_ANNOUNCEMENT"))
        assertTrue(IdentityRelayPolicy.isIdentityType("SYSTEM_PROFILE"))
        assertTrue(IdentityRelayPolicy.isIdentityType("IDENTITY_REQUEST"))
        assertFalse(IdentityRelayPolicy.isIdentityType("PUBLIC"))
        assertFalse(IdentityRelayPolicy.isIdentityType("PRIVATE"))
    }

    @Test
    fun mayRelay_blocksAllIdentity() {
        assertFalse(IdentityRelayPolicy.mayRelay("IDENTITY_ANNOUNCEMENT"))
        assertFalse(IdentityRelayPolicy.mayRelay("SYSTEM_PROFILE"))
        assertFalse(IdentityRelayPolicy.mayRelay("IDENTITY_REQUEST"))
        assertTrue(IdentityRelayPolicy.mayRelay("PUBLIC"))
    }

    @Test
    fun acceptDirect_requiresFullTtlAndEmptyHops() {
        val def = 7
        assertTrue(IdentityRelayPolicy.acceptDirectIdentity(ttl = 7, hopHistorySize = 0, defaultTtl = def))
        assertFalse(IdentityRelayPolicy.acceptDirectIdentity(ttl = 6, hopHistorySize = 0, defaultTtl = def))
        assertFalse(IdentityRelayPolicy.acceptDirectIdentity(ttl = 7, hopHistorySize = 1, defaultTtl = def))
        assertFalse(IdentityRelayPolicy.acceptDirectIdentity(ttl = 1, hopHistorySize = 2, defaultTtl = def))
    }
}
