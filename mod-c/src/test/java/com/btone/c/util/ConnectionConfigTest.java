package com.btone.c.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionConfigTest {
    @Test
    void roundTrip(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("nested/btone-bridge.json");
        ConnectionConfig cfg = new ConnectionConfig(25591, "tok-abc", "0.1.0");
        cfg.writeTo(p);
        assertTrue(Files.exists(p));
        ConnectionConfig back = ConnectionConfig.readFrom(p);
        assertEquals(cfg, back);
    }

    @Test
    void overwriteAtomically(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("btone-bridge.json");
        new ConnectionConfig(1, "a", "v").writeTo(p);
        new ConnectionConfig(2, "b", "v").writeTo(p);
        ConnectionConfig back = ConnectionConfig.readFrom(p);
        assertEquals(2, back.port());
        assertEquals("b", back.token());
    }
}
