package com.blink.dtn.ui

/**
 * How many other people's messages this phone is currently carrying.
 *
 * Not XP / karma / cosmetics: counts come from the existing Room queue
 * (PENDING / IN_FLIGHT / PENDING_KEY), not a parallel delivery system.
 */
enum class DeliveryLoad {
    /** 0 — calm, no indicator */
    Calm,

    /** 1–3 */
    Light,

    /** 4–9 */
    Medium,

    /** 10+ */
    Heavy;

    fun label(count: Int, lang: String = AppLang.lang.value): String = when (this) {
        Calm -> if (lang == "en") "Nothing to carry" else "Вы ничего не несёте"
        else -> if (lang == "en") "Carrying $count messages" else "Вы несёте $count сообщений"
    }

    companion object {
        fun fromQueuedCount(count: Int): DeliveryLoad = when {
            count <= 0 -> Calm
            count <= 3 -> Light
            count <= 9 -> Medium
            else -> Heavy
        }
    }
}
