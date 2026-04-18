package com.btone.c.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenTest {
    @Test
    void generates43CharBase64Url() {
        String t = Token.generate();
        assertEquals(43, t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            assertTrue(Character.isLetterOrDigit(c) || c == '-' || c == '_',
                    "non-base64url char: " + c);
        }
    }

    @Test
    void twoTokensDiffer() {
        assertNotEquals(Token.generate(), Token.generate());
    }

    @Test
    void matchesConstantTime() {
        String t = Token.generate();
        assertTrue(Token.matches(t, t));
        assertFalse(Token.matches(t, "x".repeat(t.length())));
        assertFalse(Token.matches(t, "short"));
        assertFalse(Token.matches("", t));
        assertFalse(Token.matches(null, t));
        assertFalse(Token.matches(t, null));
    }
}
