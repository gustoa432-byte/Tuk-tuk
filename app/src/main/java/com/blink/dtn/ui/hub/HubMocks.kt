package com.blink.dtn.ui.hub

import com.blink.dtn.db.MessagePriority

/** Mock radar contact after a successful visual handshake. */
data class RadarContact(
    val id: String,
    val dinoEmoji: String,
    val nick: String
)

enum class RadarPhase {
    SEARCHING,
    SIGNAL,
    HANDSHAKE
}

/** Parcel in the courier backpack (UI mock / future Room projection). */
data class ParcelItem(
    val id: String,
    val title: String,
    val preview: String,
    val priority: MessagePriority,
    val hopHistory: List<String> = emptyList(),
    val delivered: Boolean = false
)

/** Completed route entry for Chronicle. */
data class ChronicleEntry(
    val id: String,
    val title: String,
    val hopHistory: List<String>,
    val deliveredAtLabel: String,
    val thanked: Boolean = false
)

object HubMocks {
    val parcels: List<ParcelItem> = listOf(
        ParcelItem(
            id = "p0",
            title = "Note to Rex",
            preview = "Meet at the bridge after dark",
            priority = MessagePriority.NORMAL,
            hopHistory = listOf("You", "🦕 Ivan")
        ),
        ParcelItem(
            id = "p1",
            title = "Map pin",
            preview = "Geo drop · 55.75, 37.61",
            priority = MessagePriority.MEDIUM,
            hopHistory = listOf("You", "🦎 Kira", "🦕 Rex")
        ),
        ParcelItem(
            id = "p2",
            title = "SOS beacon",
            preview = "Need relay — low battery",
            priority = MessagePriority.CRITICAL,
            hopHistory = listOf("You")
        ),
        ParcelItem(
            id = "p3",
            title = "Photo stub",
            preview = "Attachment pending offline",
            priority = MessagePriority.MEDIUM
        ),
        ParcelItem(
            id = "p4",
            title = "Hello mesh",
            preview = "First tuk across the block",
            priority = MessagePriority.NORMAL
        ),
        ParcelItem(
            id = "p5",
            title = "Critical relay",
            preview = "Medical coords encrypted",
            priority = MessagePriority.CRITICAL,
            hopHistory = listOf("You", "🦕 Ivan", "🦎 Kira")
        )
    )

    val chronicle: List<ChronicleEntry> = listOf(
        ChronicleEntry(
            id = "c1",
            title = "Parcel delivered to 🦕 Rex",
            hopHistory = listOf("You", "🦕 Ivan", "🦎 Kira", "🦕 Rex"),
            deliveredAtLabel = "12 min ago"
        ),
        ChronicleEntry(
            id = "c2",
            title = "Public tuk reached channel",
            hopHistory = listOf("You", "🦕 Mira"),
            deliveredAtLabel = "1 h ago"
        ),
        ChronicleEntry(
            id = "c3",
            title = "SOS ack received",
            hopHistory = listOf("You", "🦎 Kira", "🦕 Rex"),
            deliveredAtLabel = "Yesterday"
        )
    )

    val handshakeContact = RadarContact(
        id = "mock-rex",
        dinoEmoji = "🦕",
        nick = "Rex"
    )
}
