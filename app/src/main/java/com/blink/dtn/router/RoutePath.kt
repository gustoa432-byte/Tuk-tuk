package com.blink.dtn.router

/**
 * Physical path chosen by the unified message router.
 * User never picks this — [MessageRouter] does.
 */
enum class RoutePath {
    INTERNET,
    BLE;

    fun labelRu(): String = when (this) {
        INTERNET -> "Интернет"
        BLE -> "Bluetooth"
    }

    fun labelEn(): String = when (this) {
        INTERNET -> "Internet"
        BLE -> "Bluetooth"
    }

    fun traceId(): String = when (this) {
        INTERNET -> "internet"
        BLE -> "ble"
    }
}

data class RouteDecision(
    val path: RoutePath,
    val reasonRu: String,
    val reasonEn: String
)
