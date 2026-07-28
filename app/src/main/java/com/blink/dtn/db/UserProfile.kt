package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val userId: String,
    val nickname: String,
    val lastSeen: Long,
    val isVip: Boolean,
    val publicKey: String = "",
    val giftRoses: Int = 0,
    val giftBears: Int = 0,
    val giftDiamonds: Int = 0,
    val giftCoffee: Int = 0,
    val giftRockets: Int = 0,
    val giftCrowns: Int = 0,
    /** Device-only label; does not change network nick or nodeId. */
    val localAlias: String = "",
    /**
     * STRANGER — inbound private from unknown peer (message request).
     * CONTACT — accepted / QR-scanned / user-initiated.
     * BLOCKED — ignored; further private ingress is dropped locally.
     */
    val trustStatus: String = TRUST_CONTACT
) {
    companion object {
        const val TRUST_STRANGER = "STRANGER"
        const val TRUST_CONTACT = "CONTACT"
        const val TRUST_BLOCKED = "BLOCKED"
    }

    val isContact: Boolean get() = trustStatus == TRUST_CONTACT
    val isStranger: Boolean get() = trustStatus == TRUST_STRANGER
    val isBlocked: Boolean get() = trustStatus == TRUST_BLOCKED

    /** Local alias if set, else network nick, else node id. */
    fun displayLabel(fallback: String? = null): String {
        val alias = localAlias.trim()
        if (alias.isNotEmpty()) return alias
        val nick = nickname.trim()
        if (nick.isNotEmpty()) return nick
        return fallback?.takeIf { it.isNotBlank() } ?: userId
    }
}
