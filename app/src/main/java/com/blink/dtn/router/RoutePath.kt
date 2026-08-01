package com.blink.dtn.router

/**
 * Physical path chosen by the unified message router.
 * User never picks this — [MessageRouter] does.
 */
enum class RoutePath {
    INTERNET,
    WIFI_DIRECT,
    BLE;

    fun labelRu(): String = when (this) {
        INTERNET -> "Интернет"
        WIFI_DIRECT -> "Wi‑Fi Direct"
        BLE -> "Bluetooth"
    }

    fun labelEn(): String = when (this) {
        INTERNET -> "Internet"
        WIFI_DIRECT -> "Wi‑Fi Direct"
        BLE -> "Bluetooth"
    }

    fun traceId(): String = when (this) {
        INTERNET -> "internet"
        WIFI_DIRECT -> "wifi_direct"
        BLE -> "ble"
    }
}

data class RouteDecision(
    val path: RoutePath,
    val reasonRu: String,
    val reasonEn: String
)
