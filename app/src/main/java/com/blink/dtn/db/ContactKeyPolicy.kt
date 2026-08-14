package com.blink.dtn.db

import com.blink.dtn.ble.KeyChangeAlerts
import com.blink.dtn.crypto.NodeIdentity

/**
 * Local address book owns keys. A discovered key is TOFU; a QR pin is VERIFIED.
 * A different advertised key is never written silently.
 */
object ContactKeyPolicy {

    enum class Merge {
        /** No local key yet — store the advertised one. */
        Tofu,
        /** Same key, or nothing advertised. */
        Unchanged,
        /** Advertised key differs — keep the old one and ask for QR. */
        KeyChangedKeptOld,
        /** Advertised key does not derive to the claimed node id. */
        Rejected
    }

    fun advertisedDerivesTo(nodeId: String, advertisedKey: String): Boolean {
        if (advertisedKey.isBlank() || nodeId.isBlank()) return false
        val derived = NodeIdentity.deriveNodeId(advertisedKey)
        return derived.isNotBlank() && derived == nodeId
    }

    fun merge(
        existingKey: String,
        advertisedKey: String,
        advertisedDerivesToNode: Boolean
    ): Merge {
        if (advertisedKey.isBlank()) return Merge.Unchanged
        if (!advertisedDerivesToNode) return Merge.Rejected
        if (existingKey.isBlank()) return Merge.Tofu
        if (existingKey == advertisedKey) return Merge.Unchanged
        return Merge.KeyChangedKeptOld
    }

    /**
     * Apply a key learned from the internet envelope or username lookup.
     * Never sets [UserProfile.verifiedOutOfBand]. New rows are STRANGER when
     * [asStrangerIfNew], otherwise CONTACT (user-initiated find).
     *
     * @return the merge outcome after Room write (if any).
     */
    suspend fun applyDiscovered(
        dao: BLinkDao,
        nodeId: String,
        advertisedKey: String,
        asStrangerIfNew: Boolean,
        username: String = "",
        nick: String = ""
    ): Merge {
        val id = nodeId.trim()
        if (id.isEmpty()) return Merge.Rejected
        val existing = dao.getProfileById(id)
        if (existing?.isBlocked == true) return Merge.Unchanged
        val derivedOk = advertisedDerivesTo(id, advertisedKey)
        val outcome = merge(existing?.publicKey.orEmpty(), advertisedKey, derivedOk)
        when (outcome) {
            Merge.Rejected -> return outcome
            Merge.KeyChangedKeptOld -> {
                KeyChangeAlerts.notify(id)
                return outcome
            }
            Merge.Unchanged -> {
                if (existing != null && username.isNotBlank() && existing.username.isBlank()) {
                    dao.insertOrUpdateProfile(existing.copy(username = username))
                }
                return outcome
            }
            Merge.Tofu -> {
                val now = System.currentTimeMillis()
                val trust = when {
                    existing?.isBlocked == true -> UserProfile.TRUST_BLOCKED
                    existing != null -> existing.trustStatus
                    asStrangerIfNew -> UserProfile.TRUST_STRANGER
                    else -> UserProfile.TRUST_CONTACT
                }
                val profile = (existing ?: UserProfile(
                    userId = id,
                    nickname = nick.ifBlank { username.ifBlank { id } },
                    lastSeen = now,
                    isVip = false
                )).copy(
                    lastSeen = now,
                    publicKey = advertisedKey,
                    trustStatus = trust,
                    verifiedOutOfBand = existing?.verifiedOutOfBand ?: false,
                    username = username.ifBlank { existing?.username.orEmpty() },
                    nickname = when {
                        nick.isNotBlank() -> nick
                        existing?.nickname?.isNotBlank() == true -> existing.nickname
                        username.isNotBlank() -> username
                        else -> existing?.nickname ?: id
                    }
                )
                dao.insertOrUpdateProfile(profile)
                return outcome
            }
        }
    }
}
