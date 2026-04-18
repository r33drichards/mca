package com.btone.b

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenTest {
    @Test fun `generates 256 bits base64url`() {
        val t = Token.generate()
        assertEquals(43, t.length, "base64url 32 bytes = 43 chars no padding")
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun `two generations differ`() {
        assertNotEquals(Token.generate(), Token.generate())
    }

    @Test fun `matches returns correct result`() {
        val t = Token.generate()
        assertTrue(Token.matches(t, t))
        assertFalse(Token.matches(t, "x".repeat(t.length)))
        assertFalse(Token.matches(t, "short"))
        assertFalse(Token.matches("", t))
    }
}
