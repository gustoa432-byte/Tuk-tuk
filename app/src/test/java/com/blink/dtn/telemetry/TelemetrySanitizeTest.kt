package com.blink.dtn.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySanitizeTest {
    @Test
    fun stripsMessageBodyKeys() {
        val scrubbed = TelemetrySanitize.scrubDetails(
            mapOf(
                "messageLength" to "12",
                "text" to "secret hello",
                "message_body" to "nope",
                "status" to "ok"
            )
        )
        assertEquals("12", scrubbed["messageLength"])
        assertEquals("ok", scrubbed["status"])
        assertFalse(scrubbed.containsKey("text"))
        assertFalse(scrubbed.containsKey("message_body"))
        assertTrue(scrubbed.size == 2)
    }
}
