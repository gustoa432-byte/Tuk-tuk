package com.blink.dtn.moderation

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory mirror of Room `banned_nodes` for hot-path BLE / router checks.
 * Updated by [com.blink.dtn.net.VpsBridge] after blacklist sync.
 */
object GlobalBanCache {
    private val banned = ConcurrentHashMap.newKeySet<String>()

    fun replaceAll(nodeIds: Collection<String>) {
        banned.clear()
        for (id in nodeIds) {
            val t = id.trim()
            if (t.isNotEmpty()) banned.add(t)
        }
    }

    fun isBanned(nodeId: String?): Boolean {
        val id = nodeId?.trim().orEmpty()
        return id.isNotEmpty() && banned.contains(id)
    }

    fun snapshot(): Set<String> = banned.toSet()

    fun size(): Int = banned.size
}
