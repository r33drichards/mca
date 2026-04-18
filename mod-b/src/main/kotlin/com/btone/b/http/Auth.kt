package com.btone.b.http

import com.btone.b.Token

sealed class AuthResult {
    object Ok : AuthResult()
    object Missing : AuthResult()
    object BadScheme : AuthResult()
    object Forbidden : AuthResult()
}

object Auth {
    fun check(headers: Map<String, List<String>>, expectedToken: String): AuthResult {
        val h = headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: return AuthResult.Missing
        if (!h.startsWith("Bearer ", ignoreCase = true)) return AuthResult.BadScheme
        return if (Token.matches(expectedToken, h.substring(7).trim())) AuthResult.Ok else AuthResult.Forbidden
    }
}
