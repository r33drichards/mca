package com.btone.c.util;

import java.security.SecureRandom;
import java.util.Base64;

/** Cryptographic token generator + constant-time compare for bearer auth. */
public final class Token {
    private static final SecureRandom RNG = new SecureRandom();

    private Token() {}

    /** 256-bit base64url token, no padding (43 chars). */
    public static String generate() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Constant-time string equality (length-prefixed). */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        if (expected.length() != actual.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }
}
