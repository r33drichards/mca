package com.btone.b

import java.security.SecureRandom
import java.util.Base64

object Token {
    private val rng = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32).also(rng::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun matches(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }
}
