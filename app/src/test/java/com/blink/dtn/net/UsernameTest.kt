package com.blink.dtn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameTest {

    @Test
    fun normalizeStripsAtAndCase() {
        assertEquals("alice_1", Username.normalize(" @Alice_1 "))
        assertEquals("bob", Username.normalize("bob"))
    }

    @Test
    fun exactCharsetNoWildcards() {
        assertTrue(Username.isValid("alice"))
        assertTrue(Username.isValid("a_1"))
        assertFalse(Username.isValid("ab"))
        assertFalse(Username.isValid("alice-1"))
        assertFalse(Username.isValid("alice%"))
        assertFalse(Username.isValid("not valid"))
        assertFalse(Username.isValid("qqube_official"))
        assertFalse(Username.isValid("qq"))
    }
}
