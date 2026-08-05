package com.blink.dtn.telemetry

/**
 * Defense-in-depth scrub before ZIP export.
 * Models must never carry private keys or PRIVATE plaintext; this also strips
 * accidental sensitive keys/values from TraceEvent.details.
 */
object TelemetrySanitize {
    private val DENY_KEY_SUBSTR = listOf(
        "private_key", "privatekey", "privkey",
        "plaintext", "plain_text", "message_text", "message_body", "msg_text",
        "jwt", "bearer", "access_token", "refresh_token",
        "password", "secret", "smtp_pass", "bot_token",
        "cipher_text", "ciphertext", "aes_key", "wrapped_key"
    )

    fun scrubTrace(trace: MessageTrace): MessageTrace {
        val events = trace.events.map { e ->
            e.copy(details = scrubDetails(e.details))
        }.toMutableList()
        return MessageTrace(
            traceId = trace.traceId,
            kind = trace.kind,
            messageId = trace.messageId,
            conversationId = trace.conversationId,
            targetId = trace.targetId,
            senderId = trace.senderId,
            messageType = trace.messageType,
            startedAt = trace.startedAt,
            finishedAt = trace.finishedAt,
            terminalStatus = trace.terminalStatus,
            events = events,
            visualSteps = trace.visualSteps.toMutableList()
        )
    }

    fun scrubDetails(details: Map<String, String>): Map<String, String> {
        if (details.isEmpty()) return details
        val out = LinkedHashMap<String, String>(details.size)
        for ((k, v) in details) {
            val lk = k.lowercase()
            if (DENY_KEY_SUBSTR.any { lk.contains(it) }) continue
            // Length/stats keys are fine; huge free-form values (e.g. stack) stay capped.
            out[k] = if (v.length > 2_000) "[redacted_len=${v.length}]" else v
        }
        return out
    }

    fun scrubTraces(traces: List<MessageTrace>): List<MessageTrace> =
        traces.map { scrubTrace(it) }
}
