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
    val trustStatus: String = TRUST_CONTACT,
    /**
     * True when public key was pinned out-of-band (QR). Distinguishes «проверен»
     * from mesh-discovered «из сети» contacts.
     */
    val verifiedOutOfBand: Boolean = false,
    /** Last advertised app versionCode from IDENTITY_ANNOUNCEMENT (0 = unknown). */
    val appVersionCode: Long = 0L,
    /** Last advertised app versionName from IDENTITY_ANNOUNCEMENT. */
    val appVersionName: String = "",
    /**
     * Mesh-sized avatar JPEG bytes (square, ≤~128px). Null = none.
     * Stored locally and optionally embedded in contact QR as base64 `av`.
     */
    val avatarBlob: ByteArray? = null
) {
    companion object {
        const val TRUST_STRANGER = "STRANGER"
        const val TRUST_CONTACT = "CONTACT"
        const val TRUST_BLOCKED = "BLOCKED"
    }

    val isContact: Boolean get() = trustStatus == TRUST_CONTACT
    val isStranger: Boolean get() = trustStatus == TRUST_STRANGER
    val isBlocked: Boolean get() = trustStatus == TRUST_BLOCKED
    val isVerified: Boolean get() = verifiedOutOfBand && isContact

    /** Local alias if set, else network nick, else node id. */
    fun displayLabel(fallback: String? = null): String {
        val alias = localAlias.trim()
        if (alias.isNotEmpty()) return alias
        val nick = nickname.trim()
        if (nick.isNotEmpty()) return nick
        return fallback?.takeIf { it.isNotBlank() } ?: userId
    }

    fun shortId(chars: Int = 8): String {
        val id = userId.trim()
        if (id.length <= chars) return id
        return id.take(chars)
    }

    fun trustBadge(lang: String = "ru"): String = if (lang == "en") when {
        isBlocked -> "blocked"
        isStranger -> "stranger"
        isVerified -> "verified"
        else -> "not verified"
    } else when {
        isBlocked -> "блок"
        isStranger -> "незнакомец"
        isVerified -> "проверен"
        else -> "не сверен"
    }

    @Deprecated("Use trustBadge(lang)", replaceWith = ReplaceWith("trustBadge()"))
    fun trustBadgeRu(): String = trustBadge("ru")
}
