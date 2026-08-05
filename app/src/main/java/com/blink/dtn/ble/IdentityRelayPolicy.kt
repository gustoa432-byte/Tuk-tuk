package com.blink.dtn.ble

/**
 * IDENTITY packets are 1-hop only: learn a neighbor's key from a direct GATT write.
 * Never store-and-forward IDENTITY* — that floods RF under density.
 */
object IdentityRelayPolicy {
    fun isIdentityType(type: String): Boolean =
        type == "IDENTITY_ANNOUNCEMENT" ||
            type == "SYSTEM_PROFILE" ||
            type == "IDENTITY_REQUEST"

    /**
     * Accept only fresh direct announces: full hop budget and empty custody chain.
     * Relayed copies (decremented TTL or hopHistory) are dropped silently.
     */
    fun acceptDirectIdentity(ttl: Int, hopHistorySize: Int, defaultTtl: Int): Boolean =
        hopHistorySize == 0 && ttl >= defaultTtl

    /** Never enqueue IDENTITY for epidemic relay. */
    fun mayRelay(type: String): Boolean = !isIdentityType(type)
}
