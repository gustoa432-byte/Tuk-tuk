package com.blink.dtn.db

/**
 * Gamified parcel rarity / urgency for the Human Layer.
 * Stored as Int on [Message.priority] for Room + wire compatibility.
 */
enum class MessagePriority(val code: Int) {
    NORMAL(0),
    MEDIUM(1),
    CRITICAL(2);

    companion object {
        fun fromCode(code: Int): MessagePriority =
            entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
