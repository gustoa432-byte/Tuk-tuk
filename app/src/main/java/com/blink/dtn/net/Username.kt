package com.blink.dtn.net

/** Opt-in Qq address: exact lookup, not a public profile. */

object Username {
    private val PATTERN = Regex("^[a-z0-9_]{3,20}$")
    private val RESERVED = setOf(
        "qqube_official",
        "qqube",
        "qq",
        "tuktuk",
        "admin",
        "official",
        "support",
        "system"
    )

    fun normalize(raw: String): String =
        raw.trim().removePrefix("@").lowercase()

    fun isValid(normalized: String): Boolean =
        PATTERN.matches(normalized) && normalized !in RESERVED
}
