package com.btone.b

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionConfigTest {
    @Test fun `writes and reads back`(@TempDir dir: Path) {
        val cfg = ConnectionConfig(port = 25590, token = "abc", version = "0.1.0")
        val path = dir / "btone-bridge.json"
        cfg.writeTo(path)
        assertTrue(path.exists())
        val round = ConnectionConfig.readFrom(path)
        assertEquals(cfg, round)
    }

    @Test fun `atomic write replaces old`(@TempDir dir: Path) {
        val path = dir / "btone-bridge.json"
        ConnectionConfig(1, "a", "0.1.0").writeTo(path)
        ConnectionConfig(2, "b", "0.1.0").writeTo(path)
        assertEquals(2, ConnectionConfig.readFrom(path).port)
    }
}
