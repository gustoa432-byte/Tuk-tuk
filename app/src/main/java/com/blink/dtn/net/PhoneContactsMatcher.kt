package com.blink.dtn.net

/**
 * In-memory match of a local phone book against hashed lookup hits.
 * Never persists numbers. One Qq contact per nodeId.
 */
object PhoneContactsMatcher {

    const val BATCH_SIZE = 200
    const val VERIFIED_OUT_OF_BAND = false

    data class DeviceContact(
        val contactId: Long,
        val displayName: String,
        val rawNumbers: List<String>
    )

    data class NormalizedNumber(
        val contactId: Long,
        val displayName: String,
        val e164: String,
        val hash: String
    )

    data class InQq(
        val nodeId: String,
        val publicKey: String,
        val username: String,
        val displayName: String,
        val contactId: Long
    )

    data class Invite(
        val contactId: Long,
        val displayName: String
    )

    data class Plan(
        val inQq: List<InQq>,
        val invite: List<Invite>
    )

    enum class Gate { NeedExplain, NeedPermission, Denied, Ready }

    fun gate(explained: Boolean, permissionGranted: Boolean): Gate = when {
        permissionGranted -> Gate.Ready
        !explained -> Gate.NeedExplain
        else -> Gate.Denied
    }

    fun normalizeBook(
        contacts: List<DeviceContact>,
        defaultRegion: String = PhoneE164.DEFAULT_REGION
    ): List<NormalizedNumber> {
        val out = ArrayList<NormalizedNumber>()
        val seen = HashSet<String>()
        for (c in contacts) {
            val name = c.displayName.trim().ifBlank { c.rawNumbers.firstOrNull().orEmpty() }
            for (raw in c.rawNumbers) {
                val e164 = PhoneE164.normalize(raw, defaultRegion) ?: continue
                val hash = PhoneE164.sha256Hex(e164)
                val key = "${c.contactId}|$hash"
                if (!seen.add(key)) continue
                out += NormalizedNumber(c.contactId, name, e164, hash)
            }
        }
        return out
    }

    fun uniqueHashes(numbers: List<NormalizedNumber>): List<String> {
        val seen = LinkedHashSet<String>()
        for (n in numbers) seen.add(n.hash)
        return seen.toList()
    }

    fun chunkHashes(hashes: List<String>, batch: Int = BATCH_SIZE): List<List<String>> {
        if (hashes.isEmpty()) return emptyList()
        return hashes.chunked(batch.coerceAtLeast(1))
    }

    fun match(
        numbers: List<NormalizedNumber>,
        hits: List<PhoneHit>,
        myNodeId: String = ""
    ): Plan {
        val byHash = HashMap<String, PhoneHit>()
        for (h in hits) {
            if (h.exists && !h.nodeId.isNullOrBlank() && !h.publicKey.isNullOrBlank()) {
                byHash[h.hash] = h
            }
        }
        val inQqByNode = LinkedHashMap<String, InQq>()
        val matchedContactIds = HashSet<Long>()
        for (n in numbers) {
            val hit = byHash[n.hash] ?: continue
            val nodeId = hit.nodeId.orEmpty()
            if (nodeId.isBlank() || nodeId == myNodeId) {
                matchedContactIds += n.contactId
                continue
            }
            matchedContactIds += n.contactId
            if (nodeId in inQqByNode) continue
            inQqByNode[nodeId] = InQq(
                nodeId = nodeId,
                publicKey = hit.publicKey.orEmpty(),
                username = hit.username.orEmpty(),
                displayName = n.displayName,
                contactId = n.contactId
            )
        }
        val invite = LinkedHashMap<Long, Invite>()
        for (n in numbers) {
            if (n.contactId in matchedContactIds) continue
            if (n.contactId in invite) continue
            val name = n.displayName.trim().ifBlank { n.e164 }
            invite[n.contactId] = Invite(n.contactId, name)
        }
        return Plan(
            inQq = inQqByNode.values.toList(),
            invite = invite.values.toList()
        )
    }

    /**
     * Permission denied must not throw — QR / @username stay available.
     */
    fun afterPermission(granted: Boolean): Gate =
        if (granted) Gate.Ready else Gate.Denied

    fun mapLookupFailure(error: Throwable): String {
        val api = error as? ApiException
        val msg = error.message.orEmpty()
        return when {
            api?.message == "need_session" || msg.contains("not_authenticated") -> "need_session"
            api?.httpCode == 401 -> "need_session"
            msg.contains("VPS URL not configured", ignoreCase = true) -> "gateway_down"
            api?.httpCode in 500..599 -> "gateway_down"
            error is java.io.IOException -> "gateway_down"
            else -> "gateway_down"
        }
    }
}
