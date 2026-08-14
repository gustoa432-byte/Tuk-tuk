package com.blink.dtn.net

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.security.MessageDigest

/**
 * One E.164 form per number, then SHA-256 hex for gateway lookup.
 * Default region RU so `8 999…` / `7999…` / `+7 999…` collapse to the same hash.
 */
object PhoneE164 {
    const val DEFAULT_REGION = "RU"

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    fun normalize(raw: String, defaultRegion: String = DEFAULT_REGION): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val parsed = util.parse(trimmed, defaultRegion)
            if (!util.isPossibleNumber(parsed)) return null
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            null
        }
    }

    fun sha256Hex(e164: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(e164.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun hashNormalized(raw: String, defaultRegion: String = DEFAULT_REGION): String? {
        val e164 = normalize(raw, defaultRegion) ?: return null
        return sha256Hex(e164)
    }
}
