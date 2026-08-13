package com.blink.dtn.ui

/**
 * Visual metaphor for mesh carry load — not XP / karma / cosmetics.
 *
 * Counts come from existing Room queue (PENDING / IN_FLIGHT / PENDING_KEY),
 * not a parallel delivery system.
 *
 * Name options (product TBD):
 * - «Рюкзак» / Backpack
 * - «Нагрузка» / Delivery load
 * - «Заряд сети» / Social charge
 * - «Ноша» / Carry
 */
enum class DeliveryLoad {
    /** 0 — calm, no indicator */
    Calm,
    /** few — yellow */
    Light,
    /** several — orange */
    Medium,
    /** many — red */
    Heavy;

    companion object {
        /** Tentative thresholds — not final product numbers. */
        fun fromQueuedCount(count: Int): DeliveryLoad = when {
            count <= 0 -> Calm
            count <= 2 -> Light
            count <= 9 -> Medium
            else -> Heavy
        }
    }
}
