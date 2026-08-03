package com.blink.dtn.ble

/**
 * Hard product limits for mesh / DTN text payloads.
 * Photos never go over mesh — see MessageRouter / VpsBridge.
 */
object MeshLimits {
    /** Max Unicode code units (Kotlin String.length) for PUBLIC/PRIVATE chat text on mesh. */
    const val MAX_TEXT_CHARS = 140

    fun isChatTextType(type: String): Boolean =
        type == "PUBLIC" || type == "PRIVATE"

    fun exceedsTextLimit(text: String): Boolean =
        text.length > MAX_TEXT_CHARS

    fun clampText(text: String): String =
        if (text.length <= MAX_TEXT_CHARS) text else text.take(MAX_TEXT_CHARS)
}
