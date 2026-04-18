package com.btone.b.http

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AuthTest {
    @Test fun `accepts correct bearer`() {
        val t = "secret"
        assertEquals(AuthResult.Ok, Auth.check(mapOf("Authorization" to listOf("Bearer $t")), t))
    }

    @Test fun `rejects missing`() {
        assertEquals(AuthResult.Missing, Auth.check(emptyMap(), "x"))
    }

    @Test fun `rejects wrong scheme`() {
        assertEquals(AuthResult.BadScheme, Auth.check(mapOf("Authorization" to listOf("Basic x")), "x"))
    }

    @Test fun `rejects wrong token`() {
        assertEquals(AuthResult.Forbidden, Auth.check(mapOf("Authorization" to listOf("Bearer wrong")), "right"))
    }
}
