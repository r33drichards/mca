package com.btone.c.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @Test
    void acceptsCorrectBearer() {
        assertEquals(Auth.Result.OK,
                Auth.check(Map.of("Authorization", List.of("Bearer secret")), "secret"));
    }

    @Test
    void caseInsensitiveHeader() {
        assertEquals(Auth.Result.OK,
                Auth.check(Map.of("authorization", List.of("Bearer secret")), "secret"));
    }

    @Test
    void caseInsensitiveScheme() {
        assertEquals(Auth.Result.OK,
                Auth.check(Map.of("Authorization", List.of("bearer secret")), "secret"));
    }

    @Test
    void missingHeader() {
        assertEquals(Auth.Result.MISSING, Auth.check(Map.of(), "x"));
    }

    @Test
    void badScheme() {
        assertEquals(Auth.Result.BAD_SCHEME,
                Auth.check(Map.of("Authorization", List.of("Basic x")), "x"));
    }

    @Test
    void forbiddenWrongToken() {
        assertEquals(Auth.Result.FORBIDDEN,
                Auth.check(Map.of("Authorization", List.of("Bearer wrong")), "right"));
    }

    @Test
    void emptyValuesIsMissing() {
        assertEquals(Auth.Result.MISSING,
                Auth.check(Map.of("Authorization", List.of()), "x"));
    }
}
