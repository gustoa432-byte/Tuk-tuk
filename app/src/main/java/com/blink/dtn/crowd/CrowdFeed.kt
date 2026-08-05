package com.blink.dtn.crowd

import com.blink.dtn.ble.CrowdFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory feed for Crowd «Nearby» short messages (not Room DTN backlog).
 */
object CrowdFeed {
    private const val CAP = 200
    private val items = CopyOnWriteArrayList<CrowdFeedItem>()
    private val _feed = MutableStateFlow<List<CrowdFeedItem>>(emptyList())
    val feed: StateFlow<List<CrowdFeedItem>> = _feed.asStateFlow()

    fun add(
        kind: Byte,
        text: String,
        fromHash: Int,
        roomId: String?,
        mine: Boolean = false
    ) {
        val item = CrowdFeedItem(
            id = System.currentTimeMillis().toString(36) + "-" + text.hashCode(),
            kind = kind,
            text = text.take(CrowdFrame.MAX_TEXT),
            fromHash = fromHash,
            roomId = roomId,
            mine = mine,
            at = System.currentTimeMillis()
        )
        items.add(0, item)
        while (items.size > CAP) items.removeAt(items.lastIndex)
        _feed.value = items.toList()
    }

    fun clear() {
        items.clear()
        _feed.value = emptyList()
    }
}

data class CrowdFeedItem(
    val id: String,
    val kind: Byte,
    val text: String,
    val fromHash: Int,
    val roomId: String?,
    val mine: Boolean,
    val at: Long
)
