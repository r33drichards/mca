package com.btone.c.http;

import com.btone.c.util.Token;

import java.util.List;
import java.util.Map;

public final class Auth {
    public enum Result { OK, MISSING, BAD_SCHEME, FORBIDDEN }

    private Auth() {}

    public static Result check(Map<String, List<String>> headers, String expected) {
        String h = null;
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Authorization")) {
                List<String> v = e.getValue();
                h = (v == null || v.isEmpty()) ? null : v.get(0);
                break;
            }
        }
        if (h == null) return Result.MISSING;
        if (!h.regionMatches(true, 0, "Bearer ", 0, 7)) return Result.BAD_SCHEME;
        return Token.matches(expected, h.substring(7).trim()) ? Result.OK : Result.FORBIDDEN;
    }
}
