package com.blink.dtn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class PhoneE164Test {

    @Test
    fun ruFormatsCollapseToOneE164() {
        val a = PhoneE164.normalize("+7 999 123-45-67")
        val b = PhoneE164.normalize("79991234567")
        val c = PhoneE164.normalize("8 999 123 45 67")
        assertEquals("+79991234567", a)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun sameNumberSameHash() {
        val h1 = PhoneE164.hashNormalized("+7 999 123-45-67")
        val h2 = PhoneE164.hashNormalized("79991234567")
        val h3 = PhoneE164.hashNormalized("8 999 123 45 67")
        assertNotNull(h1)
        assertEquals(h1, h2)
        assertEquals(h1, h3)
        assertEquals(64, h1!!.length)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("+79991234567".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, h1)
    }

    @Test
    fun blankIsNotANumber() {
        assertNull(PhoneE164.normalize("   "))
        assertNull(PhoneE164.hashNormalized(""))
    }
}
