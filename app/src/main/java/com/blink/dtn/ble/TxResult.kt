package com.blink.dtn.ble

/**
 * Result of a best-effort mesh TX attempt (neighbor write, not end-to-end ACK).
 */
sealed class TxResult {
    data class Success(val msgId: String) : TxResult()
    data class Failure(val msgId: String, val failedMacs: List<String> = emptyList()) : TxResult()
}
