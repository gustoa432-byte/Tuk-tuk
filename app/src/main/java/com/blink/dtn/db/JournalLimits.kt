package com.blink.dtn.db

/**
 * Shared limits for the Two Journals (predictive-routing prep).
 */
object JournalLimits {
    /** Journal B — durable seen-cache hard cap (LRU by receivedAt). */
    const val SEEN_CACHE_CAP = 10_000

    /** Journal B / messages — meteor cool-down window. */
    const val SEEN_TTL_MS = 48L * 60L * 60L * 1000L

    /**
     * Journal A — minimum gap between meet_count increments for the same node.
     * Prevents GATT reconnect spam from inflating the social orbit.
     */
    const val MEET_COOLDOWN_MS = 5L * 60L * 1000L
}
