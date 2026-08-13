package com.blink.dtn.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A neighbour's public key changed on the wire. We keep the old key until the
 * user confirms by scanning their QR — silent accept is a MITM window.
 */
object KeyChangeAlerts {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun notify(nodeId: String) {
        val id = nodeId.trim()
        if (id.isEmpty()) return
        _events.tryEmit(id)
    }
}
