# Option C: `btone-mod-c` Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A Fabric 1.21.8 client mod that exposes a fixed, primitive RPC surface over localhost HTTP — `POST /rpc {method, params}` and `GET /events` SSE. No scripting, no eval, no MCP inside the JVM. The agent drives it by writing JavaScript in **mcp-v8** (https://github.com/r33drichards/mcp-js), which runs in a separate process and calls into the mod via `fetch()`.

**Architecture:** Single Fabric mod, **pure Java**, using the JDK built-in `com.sun.net.httpserver.HttpServer`. A string-keyed `RpcRouter` dispatches `{method, params}` to handler objects. All handler bodies trampoline through `MinecraftClient.submit { ... }.get()`. Event bus feeds SSE subscribers. Meteor integration is an optional set of handlers loaded via reflection when Meteor is on the classpath. The MCP layer lives entirely outside the mod — **mcp-v8 is the MCP server the agent talks to**, and the mod is just an HTTP API.

**Tech Stack:**
- Fabric Loader 0.16+, Fabric API, Java 21
- Minecraft 1.21.8
- Gradle 8.10, Loom 1.7+
- `baritone-api-fabric-1.15.0.jar` (vendored `libs/`)
- JDK `com.sun.net.httpserver.HttpServer` (zero extra dep)
- `jackson-databind` for JSON
- JUnit 5 for pure-logic tests
- **mcp-v8** (binary) — launched separately by the developer, not shipped in this repo

**Testing Note:** Same as Plan B — pure-logic components (router, event bus, token, config, param-schema validation) get unit tests. Every handler that touches `MinecraftClient` is verified via `curl` against a running Prism instance. No `MinecraftClient` mocking.

**Assumed prior knowledge:** None. Each task includes background reminders.

**Relationship to Plan B:** Several files repeat patterns from B — `Token`, `ConnectionConfig`, `Auth`, the SSE endpoint, `ClientThread`. To avoid copy-paste, the `mod-c` project duplicates them in its own package tree rather than extracting a shared module. That choice is deliberate: B and C are being built in parallel as a comparison, so sharing code across them would couple the experiments. Duplication is the right answer here; when we pick a winner we'll extract.

---

## Prelude: One-time environment setup

(Mostly same as Plan B. The Prism instance can be reused.)

**P1.** Prism Launcher installed.
**P2.** Second Prism instance named `btone-c-dev` (or reuse `btone-b-dev` but do not run B and C at the same time — they'd fight for port 25591).
**P3.** Install Fabric API, Fabric Language Kotlin is **not needed** (C is pure Java), Baritone (standalone-fabric-1.15.0), optionally Meteor.
**P4.** Install `mcp-v8`:
```bash
curl -fsSL https://raw.githubusercontent.com/r33drichards/mcp-js/main/install.sh | sudo bash
which mcp-v8  # /usr/local/bin/mcp-v8
mcp-v8 --help
```
**P5.** Install JDK 21 via Homebrew.

---

### Task 1: Gradle bootstrap (Java-only)

**Files:**
- Create: `mod-c/settings.gradle.kts`
- Create: `mod-c/build.gradle.kts`
- Create: `mod-c/gradle.properties`
- Create: `mod-c/src/main/resources/fabric.mod.json`
- Create: `mod-c/src/main/java/com/btone/c/BtoneC.java`

**Step 1.1: `mod-c/gradle.properties`.**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=1.21.8
yarn_mappings=1.21.8+build.1
loader_version=0.16.14
fabric_version=0.110.5+1.21.8

mod_version=0.1.0
maven_group=com.btone
archives_base_name=btone-mod-c
```

**Step 1.2: `mod-c/settings.gradle.kts`.**

```kotlin
pluginManagement {
    repositories { maven("https://maven.fabricmc.net/"); mavenCentral(); gradlePluginPortal() }
}
rootProject.name = "btone-mod-c"
```

**Step 1.3: `mod-c/build.gradle.kts`.**

```kotlin
plugins {
    id("fabric-loom") version "1.7-SNAPSHOT"
    `java`
    `maven-publish`
}

base { archivesName = property("archives_base_name") as String }
version = property("mod_version") as String
group = property("maven_group") as String

repositories { maven("https://maven.fabricmc.net/"); mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0.jar"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.test { useJUnitPlatform() }
```

**Step 1.4: `mod-c/src/main/resources/fabric.mod.json`.**

```json
{
  "schemaVersion": 1,
  "id": "btone_mod_c",
  "version": "${version}",
  "name": "Btone Mod C (data-pipe)",
  "description": "Minecraft HTTP RPC surface driven by external MCP hosts.",
  "environment": "client",
  "entrypoints": { "client": ["com.btone.c.BtoneC"] },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.21.8",
    "java": ">=21",
    "fabric-api": "*"
  },
  "suggests": { "baritone": "*", "meteor-client": "*" }
}
```

**Step 1.5: `BtoneC.java`.**

```java
package com.btone.c;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BtoneC implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("btone-c");

    @Override
    public void onInitializeClient() {
        LOG.info("btone-mod-c initialized");
    }
}
```

**Step 1.6:** Vendor Baritone, set up Gradle wrapper, build — same commands as Plan B (Task 1.6–1.8).

```bash
mkdir -p mod-c/libs
curl -L -o mod-c/libs/baritone-api-fabric-1.15.0.jar \
  https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar
cd mod-c && gradle wrapper --gradle-version 8.10 --distribution-type all
./gradlew build
```

**Step 1.7: Manual verify.** Copy built JAR into `btone-c-dev` mods, launch, grep log for `btone-mod-c initialized`.

**Step 1.8: Commit.**
```bash
git add mod-c/
git commit -m "c: gradle bootstrap, minimal ClientModInitializer"
```

---

### Task 2: Token + ConnectionConfig (TDD)

Same pattern as Plan B Task 2 + 3, in Java. Writing out for thoroughness.

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/util/Token.java`
- Create: `mod-c/src/test/java/com/btone/c/util/TokenTest.java`
- Create: `mod-c/src/main/java/com/btone/c/util/ConnectionConfig.java`
- Create: `mod-c/src/test/java/com/btone/c/util/ConnectionConfigTest.java`

**Step 2.1: `TokenTest.java`.**

```java
package com.btone.c.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenTest {
    @Test void generates43CharBase64Url() {
        String t = Token.generate();
        assertEquals(43, t.length());
        for (int i=0;i<t.length();i++) {
            char c = t.charAt(i);
            assertTrue(Character.isLetterOrDigit(c) || c=='-' || c=='_');
        }
    }
    @Test void twoDiffer() { assertNotEquals(Token.generate(), Token.generate()); }
    @Test void matchesConstantTime() {
        String t = Token.generate();
        assertTrue(Token.matches(t, t));
        assertFalse(Token.matches(t, "x".repeat(t.length())));
        assertFalse(Token.matches(t, "short"));
        assertFalse(Token.matches("", t));
    }
}
```

**Step 2.2: `Token.java`.**

```java
package com.btone.c.util;
import java.security.SecureRandom;
import java.util.Base64;

public final class Token {
    private static final SecureRandom RNG = new SecureRandom();
    private Token() {}
    public static String generate() {
        byte[] b = new byte[32]; RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    public static boolean matches(String expected, String actual) {
        if (expected.length() != actual.length()) return false;
        int diff = 0;
        for (int i=0;i<expected.length();i++) diff |= expected.charAt(i) ^ actual.charAt(i);
        return diff == 0;
    }
}
```

**Step 2.3:** Tests pass.

**Step 2.4: `ConnectionConfig`.** Same structure as B but in Java with Jackson. Fields: `port`, `token`, `version`. Atomic write.

```java
package com.btone.c.util;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public record ConnectionConfig(int port, String token, String version) {
    private static final ObjectMapper M = new ObjectMapper();
    public void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "btone-", ".tmp");
        Files.writeString(tmp, M.writeValueAsString(this));
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static ConnectionConfig readFrom(Path path) throws IOException {
        return M.readValue(Files.readString(path), ConnectionConfig.class);
    }
}
```

Tests in `ConnectionConfigTest.java` mirror Plan B Step 3.1.

**Step 2.5: Commit.**
```bash
git add . && git commit -m "c: token generator and connection config (TDD)"
```

---

### Task 3: HTTP server + bearer auth (TDD for auth, integration for bind)

Same shape as Plan B Task 4. Java translation:

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/http/Auth.java`
- Create: `mod-c/src/main/java/com/btone/c/http/BtoneHttpServer.java`
- Create: `mod-c/src/test/java/com/btone/c/http/AuthTest.java`

**Step 3.1–3.3: TDD auth.**

```java
// AuthTest.java
package com.btone.c.http;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @Test void acceptsCorrectBearer() {
        assertEquals(Auth.Result.OK, Auth.check(Map.of("Authorization", List.of("Bearer secret")), "secret"));
    }
    @Test void missing()   { assertEquals(Auth.Result.MISSING,    Auth.check(Map.of(), "x")); }
    @Test void badScheme() { assertEquals(Auth.Result.BAD_SCHEME, Auth.check(Map.of("Authorization", List.of("Basic x")), "x")); }
    @Test void forbidden() { assertEquals(Auth.Result.FORBIDDEN,  Auth.check(Map.of("Authorization", List.of("Bearer wrong")), "right")); }
}
```

```java
// Auth.java
package com.btone.c.http;
import com.btone.c.util.Token;
import java.util.List;
import java.util.Map;

public final class Auth {
    public enum Result { OK, MISSING, BAD_SCHEME, FORBIDDEN }
    private Auth() {}
    public static Result check(Map<String, List<String>> headers, String expected) {
        String h = null;
        for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase("Authorization")) { h = e.getValue().isEmpty() ? null : e.getValue().get(0); break; }
        if (h == null) return Result.MISSING;
        if (!h.regionMatches(true, 0, "Bearer ", 0, 7)) return Result.BAD_SCHEME;
        return Token.matches(expected, h.substring(7).trim()) ? Result.OK : Result.FORBIDDEN;
    }
}
```

**Step 3.4: `BtoneHttpServer.java`.**

```java
package com.btone.c.http;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;

public final class BtoneHttpServer {
    private final HttpServer server;
    private final String token;

    public BtoneHttpServer(int port, String token, Map<String, Consumer<HttpExchange>> routes) throws IOException {
        this.token = token;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        routes.forEach((path, handler) -> server.createContext(path, (HttpHandler) ex -> {
            Auth.Result a = Auth.check(ex.getRequestHeaders(), token);
            if (a != Auth.Result.OK) { write(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
            try { handler.accept(ex); } catch (Throwable t) { write(ex, 500, "{\"error\":\"" + escape(t.getMessage()) + "\"}"); }
        }));
    }
    public int actualPort() { return server.getAddress().getPort(); }
    public void start() { server.setExecutor(null); server.start(); }
    public void stop() { server.stop(0); }

    public static void write(HttpExchange ex, int code, String body) {
        write(ex, code, body, "application/json");
    }
    public static void write(HttpExchange ex, int code, String body, String contentType) {
        try {
            byte[] bytes = body.getBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(code, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        } catch (IOException ignored) {}
    }
    private static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
```

**Step 3.5:** Wire minimal `/health` into `BtoneC.java`:

```java
@Override
public void onInitializeClient() {
    try {
        String token = Token.generate();
        var routes = Map.<String, Consumer<HttpExchange>>of(
            "/health", ex -> BtoneHttpServer.write(ex, 200, "{\"ok\":true}")
        );
        var server = new BtoneHttpServer(25591, token, routes);
        server.start();
        var cfg = new ConnectionConfig(server.actualPort(), token, "0.1.0");
        cfg.writeTo(FabricLoader.getInstance().getConfigDir().resolve("btone-bridge.json"));
        LOG.info("btone-mod-c listening on 127.0.0.1:{}", server.actualPort());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> server.stop());
    } catch (Exception e) {
        LOG.error("failed to start btone-mod-c http server", e);
    }
}
```

**Step 3.6: Manual verification.** Same shape as Plan B Task 4.6 — use port **25591**.

**Step 3.7: Commit.**
```bash
git add . && git commit -m "c: http server with bearer auth, /health endpoint"
```

---

### Task 4: RPC router + `ClientThread` helper

**Why:** A `POST /rpc` handler that reads `{method, params}`, looks up a handler, runs it on the client thread, returns `{ok, result}`.

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/ClientThread.java`
- Create: `mod-c/src/main/java/com/btone/c/rpc/RpcRouter.java`
- Create: `mod-c/src/main/java/com/btone/c/rpc/RpcHandler.java`
- Create: `mod-c/src/test/java/com/btone/c/rpc/RpcRouterTest.java`

**Step 4.1: `ClientThread.java`.**

```java
package com.btone.c;
import net.minecraft.client.MinecraftClient;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class ClientThread {
    private ClientThread() {}
    public static <T> T call(long timeoutMs, Supplier<T> block) {
        try {
            return MinecraftClient.getInstance().submit(block::get).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        catch (ExecutionException | TimeoutException e) { throw new RuntimeException(e.getCause() != null ? e.getCause() : e); }
    }
}
```

**Step 4.2: `RpcHandler.java`.**

```java
package com.btone.c.rpc;
import com.fasterxml.jackson.databind.JsonNode;
public interface RpcHandler { JsonNode handle(JsonNode params) throws Exception; }
```

**Step 4.3: TDD router.**

```java
// RpcRouterTest.java
package com.btone.c.rpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcRouterTest {
    private static final ObjectMapper M = new ObjectMapper();
    @Test void dispatches() throws Exception {
        RpcRouter r = new RpcRouter();
        r.register("echo", params -> params);
        var req = M.createObjectNode(); req.put("method", "echo"); req.putObject("params").put("x", 1);
        var resp = r.dispatch(req);
        assertTrue(resp.get("ok").asBoolean());
        assertEquals(1, resp.get("result").get("x").asInt());
    }
    @Test void unknownMethod() throws Exception {
        RpcRouter r = new RpcRouter();
        var req = M.createObjectNode(); req.put("method", "nope");
        var resp = r.dispatch(req);
        assertFalse(resp.get("ok").asBoolean());
        assertEquals("unknown_method", resp.get("error").get("code").asText());
    }
}
```

**Step 4.4: `RpcRouter.java`.**

```java
package com.btone.c.rpc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RpcRouter {
    private static final ObjectMapper M = new ObjectMapper();
    private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();

    public void register(String method, RpcHandler h) { handlers.put(method, h); }
    public Map<String, RpcHandler> all() { return handlers; }

    public ObjectNode dispatch(JsonNode req) {
        String method = req.path("method").asText();
        JsonNode params = req.path("params");
        ObjectNode resp = M.createObjectNode();
        RpcHandler h = handlers.get(method);
        if (h == null) {
            resp.put("ok", false);
            resp.putObject("error").put("code", "unknown_method").put("message", method);
            return resp;
        }
        try {
            resp.put("ok", true);
            resp.set("result", h.handle(params));
        } catch (Exception e) {
            resp.removeAll();
            resp.put("ok", false);
            resp.putObject("error").put("code", e.getClass().getSimpleName()).put("message", String.valueOf(e.getMessage()));
        }
        return resp;
    }
}
```

**Step 4.5:** Register `/rpc` in `BtoneC`:

```java
RpcRouter router = new RpcRouter();
// echo for smoke
router.register("debug.echo", params -> params);

var routes = Map.<String, Consumer<HttpExchange>>of(
    "/health", ex -> BtoneHttpServer.write(ex, 200, "{\"ok\":true}"),
    "/rpc", ex -> {
        try {
            JsonNode req = new ObjectMapper().readTree(ex.getRequestBody());
            var resp = router.dispatch(req);
            BtoneHttpServer.write(ex, 200, resp.toString());
        } catch (Exception e) {
            BtoneHttpServer.write(ex, 400, "{\"ok\":false,\"error\":{\"code\":\"bad_request\"}}");
        }
    }
);
```

**Step 4.6: Manual verify.**

```bash
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"method":"debug.echo","params":{"x":1}}'
# expected: {"ok":true,"result":{"x":1}}
```

**Step 4.7: Commit.**
```bash
git add . && git commit -m "c: rpc router with debug.echo"
```

---

### Task 5: Player handlers — `player.state`, `player.inventory`, `player.equipped`

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/PlayerHandlers.java`
- Modify: `BtoneC.java` to register them.

**Step 5.1: Implement.**

```java
package com.btone.c.handlers;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

public final class PlayerHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private PlayerHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("player.state", state());
        r.register("player.inventory", inventory());
        r.register("player.equipped", equipped());
    }

    private static RpcHandler state() {
        return params -> ClientThread.call(2_000, () -> {
            ObjectNode n = M.createObjectNode();
            var mc = MinecraftClient.getInstance();
            var p = mc.player;
            if (p == null) { n.put("inWorld", false); return n; }
            n.put("inWorld", true);
            ObjectNode pos = n.putObject("pos"); pos.put("x", p.getX()); pos.put("y", p.getY()); pos.put("z", p.getZ());
            ObjectNode rot = n.putObject("rot"); rot.put("yaw", p.getYaw()); rot.put("pitch", p.getPitch());
            n.put("health", p.getHealth());
            n.put("food", p.getHungerManager().getFoodLevel());
            n.put("dim", mc.world.getRegistryKey().getValue().toString());
            n.put("name", p.getName().getString());
            return n;
        });
    }

    private static RpcHandler inventory() {
        return params -> ClientThread.call(2_000, () -> {
            ObjectNode n = M.createObjectNode();
            var mc = MinecraftClient.getInstance();
            var p = mc.player;
            if (p == null) { n.put("inWorld", false); return n; }
            PlayerInventory inv = p.getInventory();
            n.put("hotbarSlot", inv.getSelectedSlot());
            var arr = n.putArray("main");
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                ObjectNode o = arr.addObject();
                o.put("slot", i);
                o.put("id", Registries.ITEM.getId(s.getItem()).toString());
                o.put("count", s.getCount());
            }
            return n;
        });
    }

    private static RpcHandler equipped() {
        return params -> ClientThread.call(2_000, () -> {
            ObjectNode n = M.createObjectNode();
            var p = MinecraftClient.getInstance().player;
            if (p == null) { n.put("inWorld", false); return n; }
            ItemStack main = p.getMainHandStack(); ItemStack off = p.getOffHandStack();
            n.putObject("mainHand").put("id", Registries.ITEM.getId(main.getItem()).toString()).put("count", main.getCount());
            n.putObject("offHand").put("id", Registries.ITEM.getId(off.getItem()).toString()).put("count", off.getCount());
            return n;
        });
    }
}
```

**Step 5.2:** In `BtoneC.onInitializeClient` after router init: `PlayerHandlers.registerAll(router);`.

**Step 5.3: Manual verify** (in a world):

```bash
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"method":"player.state"}'
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"method":"player.inventory"}'
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"method":"player.equipped"}'
```

**Step 5.4: Commit.**
```bash
git add . && git commit -m "c: player.state/inventory/equipped handlers"
```

---

### Task 6: World read handlers — `world.block_at`, `world.blocks_around`, `world.raycast`

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/WorldReadHandlers.java`

**Step 6.1: Implement.**

```java
package com.btone.c.handlers;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class WorldReadHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private WorldReadHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("world.block_at", blockAt());
        r.register("world.blocks_around", blocksAround());
        r.register("world.raycast", raycast());
    }

    private static ObjectNode describe(BlockState s) {
        ObjectNode n = M.createObjectNode();
        n.put("id", Registries.BLOCK.getId(s.getBlock()).toString());
        return n;  // add `state` serialization later if needed
    }

    private static RpcHandler blockAt() {
        return params -> ClientThread.call(2_000, () -> {
            int x = params.get("x").asInt(), y = params.get("y").asInt(), z = params.get("z").asInt();
            var w = MinecraftClient.getInstance().world; if (w == null) throw new IllegalStateException("no_world");
            return describe(w.getBlockState(new BlockPos(x,y,z)));
        });
    }

    private static RpcHandler blocksAround() {
        return params -> ClientThread.call(2_000, () -> {
            int r = Math.min(params.path("radius").asInt(3), 8);
            var mc = MinecraftClient.getInstance();
            var p = mc.player; if (p == null) throw new IllegalStateException("no_player");
            BlockPos center = p.getBlockPos();
            var w = mc.world;
            ObjectNode root = M.createObjectNode();
            var arr = root.putArray("blocks");
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                BlockPos pos = center.add(dx, dy, dz);
                BlockState s = w.getBlockState(pos);
                if (s.isAir()) continue;
                ObjectNode o = arr.addObject();
                o.put("x", pos.getX()); o.put("y", pos.getY()); o.put("z", pos.getZ());
                o.put("id", Registries.BLOCK.getId(s.getBlock()).toString());
            }
            return root;
        });
    }

    private static RpcHandler raycast() {
        return params -> ClientThread.call(2_000, () -> {
            double maxDist = params.path("max").asDouble(5.0);
            var mc = MinecraftClient.getInstance();
            var p = mc.player; if (p == null) throw new IllegalStateException("no_player");
            HitResult hit = p.raycast(maxDist, 1.0f, false);
            ObjectNode n = M.createObjectNode();
            n.put("type", hit.getType().name());
            if (hit instanceof BlockHitResult bhr) {
                n.put("x", bhr.getBlockPos().getX()); n.put("y", bhr.getBlockPos().getY()); n.put("z", bhr.getBlockPos().getZ());
                n.put("side", bhr.getSide().asString());
                n.put("id", Registries.BLOCK.getId(mc.world.getBlockState(bhr.getBlockPos()).getBlock()).toString());
            }
            return n;
        });
    }
}
```

Note on mappings: field/method names (`getBlockState`, `getBlockPos`, `asString`) are Yarn mappings. If the yarn version differs, some names may be slightly off — adjust against the `net.minecraft.*` classes actually present in the decompiled `minecraft-mapped.jar` after a fresh `genSources`.

**Step 6.2:** `WorldReadHandlers.registerAll(router);` in `BtoneC`.

**Step 6.3: Manual verify.** At the player's feet, `world.block_at` should return the block you're standing on. `world.raycast` while looking at something returns `id` + side.

**Step 6.4: Commit.**
```bash
git add . && git commit -m "c: world read handlers (block_at, blocks_around, raycast)"
```

---

### Task 7: Chat handlers — `chat.send`, `chat.recent`

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/ChatHandlers.java`

Chat is unusual because **incoming chat** is an event (go into `EventBus`, not polled), but we also want a `chat.recent` query so a script can get the last N without subscribing to SSE. Buffer the last 256 messages.

**Step 7.1: Implement.**

```java
package com.btone.c.handlers;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public final class ChatHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final int BUFFER_MAX = 256;
    private static final Deque<String> recent = new ArrayDeque<>();
    private ChatHandlers() {}

    public static synchronized void record(String line) {
        recent.addLast(line);
        while (recent.size() > BUFFER_MAX) recent.removeFirst();
    }

    public static void registerAll(RpcRouter r) {
        r.register("chat.send", params -> ClientThread.call(2_000, () -> {
            String text = params.get("text").asText();
            var p = MinecraftClient.getInstance().player;
            var net = MinecraftClient.getInstance().getNetworkHandler();
            if (p == null || net == null) throw new IllegalStateException("no_player");
            if (text.startsWith("/")) net.sendChatCommand(text.substring(1));
            else net.sendChatMessage(text);
            ObjectNode n = M.createObjectNode(); n.put("sent", true); return n;
        }));
        r.register("chat.recent", params -> {
            int n = Math.min(params.path("n").asInt(50), BUFFER_MAX);
            ObjectNode root = M.createObjectNode();
            var arr = root.putArray("messages");
            synchronized (recent) {
                Iterator<String> it = recent.descendingIterator();
                int i = 0;
                while (i < n && it.hasNext()) { arr.add(it.next()); i++; }
            }
            return root;
        });
    }
}
```

**Step 7.2:** `ChatHandlers.registerAll(router);`. Call `ChatHandlers.record` from the chat event registration in Task 11.

**Step 7.3: Manual verify.** In a world:

```bash
curl ... -d '{"method":"chat.send","params":{"text":"hi"}}'
# check in-game chat shows "hi"
curl ... -d '{"method":"chat.send","params":{"text":"/time set day"}}'
# world becomes day (if OP / singleplayer cheats)
```

**Step 7.4: Commit.**
```bash
git add . && git commit -m "c: chat.send and chat.recent"
```

---

### Task 8: World write — mine, place, use item, interact entity

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/WorldWriteHandlers.java`

These are the fiddly ones because they go through `mc.interactionManager`. Simplest MVP: the handler points the player's view at the target, then calls the appropriate interaction manager method.

**Step 8.1: Implement.**

```java
package com.btone.c.handlers;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WorldWriteHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private WorldWriteHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("world.mine_block", mineBlock());
        r.register("world.place_block", placeBlock());
        r.register("world.use_item", useItem());
        r.register("world.interact_entity", interactEntity());
    }

    private static RpcHandler mineBlock() {
        // Begin/progress/complete cycle is handled by BlockBreakingCooldown elsewhere.
        // Simplest version: start breaking, let tick progress, one-shot creative / instabreak works; survival requires multi-tick.
        return params -> ClientThread.call(2_000, () -> {
            int x = params.get("x").asInt(), y = params.get("y").asInt(), z = params.get("z").asInt();
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) throw new IllegalStateException("no_player");
            BlockPos pos = new BlockPos(x, y, z);
            Direction side = chooseSide(pos);
            // Look at the block center
            aimAt(Vec3d.ofCenter(pos));
            mc.interactionManager.attackBlock(pos, side);
            ObjectNode n = M.createObjectNode(); n.put("started", true); return n;
        });
    }

    private static RpcHandler placeBlock() {
        return params -> ClientThread.call(2_000, () -> {
            int x = params.get("x").asInt(), y = params.get("y").asInt(), z = params.get("z").asInt();
            String hand = params.path("hand").asText("main");
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) throw new IllegalStateException("no_player");
            BlockPos pos = new BlockPos(x, y, z);
            Direction side = chooseSide(pos);
            aimAt(Vec3d.ofCenter(pos));
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);
            var result = mc.interactionManager.interactBlock(mc.player, "main".equals(hand) ? Hand.MAIN_HAND : Hand.OFF_HAND, hit);
            ObjectNode n = M.createObjectNode(); n.put("result", result.name()); return n;
        });
    }

    private static RpcHandler useItem() {
        return params -> ClientThread.call(2_000, () -> {
            String hand = params.path("hand").asText("main");
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) throw new IllegalStateException("no_player");
            var result = mc.interactionManager.interactItem(mc.player, "main".equals(hand) ? Hand.MAIN_HAND : Hand.OFF_HAND);
            ObjectNode n = M.createObjectNode(); n.put("result", result.name()); return n;
        });
    }

    private static RpcHandler interactEntity() {
        return params -> ClientThread.call(2_000, () -> {
            int id = params.get("entityId").asInt();
            String hand = params.path("hand").asText("main");
            var mc = MinecraftClient.getInstance();
            var p = mc.player; if (p == null || mc.world == null || mc.interactionManager == null) throw new IllegalStateException("no_player");
            var e = mc.world.getEntityById(id);
            if (e == null) throw new IllegalArgumentException("no_entity");
            var r = mc.interactionManager.interactEntity(p, e, "main".equals(hand) ? Hand.MAIN_HAND : Hand.OFF_HAND);
            ObjectNode n = M.createObjectNode(); n.put("result", r.name()); return n;
        });
    }

    private static Direction chooseSide(BlockPos pos) {
        var p = MinecraftClient.getInstance().player; if (p == null) return Direction.UP;
        Vec3d eye = p.getCameraPosVec(1.0f);
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d dir = center.subtract(eye);
        // pick the dominant axis of the approach vector inverted
        if (Math.abs(dir.x) > Math.abs(dir.y) && Math.abs(dir.x) > Math.abs(dir.z)) return dir.x > 0 ? Direction.WEST : Direction.EAST;
        if (Math.abs(dir.y) > Math.abs(dir.z)) return dir.y > 0 ? Direction.DOWN : Direction.UP;
        return dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private static void aimAt(Vec3d target) {
        var p = MinecraftClient.getInstance().player; if (p == null) return;
        Vec3d eye = p.getCameraPosVec(1.0f);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYaw(yaw); p.setPitch(pitch);
    }
}
```

**Known limitation:** `world.mine_block` only *starts* mining; survival mining takes multiple ticks. For the MVP that's OK — we expose the primitive and let the agent poll. If it becomes annoying, add a `world.mine_block_sync` that loops over ticks via `mc.attackCooldown` until `isBreakingBlock` is false.

**Step 8.2: Register + manual verify.** Break/place blocks via curl, watch the world update.

**Step 8.3: Commit.**
```bash
git add . && git commit -m "c: world.mine_block / place_block / use_item / interact_entity"
```

---

### Task 9: Container handlers — `container.open/click/close/state`

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/ContainerHandlers.java`

This is slightly complex because opening a container = interacting with the block, then the server sends an `OpenScreenS2CPacket` that becomes `mc.currentScreen`. We wait (with a timeout) for `HandledScreen` to appear.

**Step 9.1: Implement** (abbreviated; use `HandledScreen` APIs for slot access):

```java
package com.btone.c.handlers;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class ContainerHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private ContainerHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("container.open", params -> ClientThread.call(3_000, () -> {
            int x = params.get("x").asInt(), y = params.get("y").asInt(), z = params.get("z").asInt();
            var mc = MinecraftClient.getInstance();
            var p = mc.player; if (p == null || mc.interactionManager == null) throw new IllegalStateException("no_player");
            BlockPos pos = new BlockPos(x,y,z);
            var hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            // Screen opens asynchronously after server reply; caller polls container.state to confirm.
            ObjectNode n = M.createObjectNode(); n.put("requested", true); return n;
        }));
        r.register("container.state", params -> ClientThread.call(2_000, () -> {
            var mc = MinecraftClient.getInstance();
            ObjectNode n = M.createObjectNode();
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) { n.put("open", false); return n; }
            n.put("open", true);
            n.put("screen", hs.getClass().getSimpleName());
            var arr = n.putArray("slots");
            var handler = hs.getScreenHandler();
            for (int i = 0; i < handler.slots.size(); i++) {
                ItemStack s = handler.slots.get(i).getStack();
                if (s.isEmpty()) continue;
                ObjectNode o = arr.addObject();
                o.put("slot", i);
                o.put("id", Registries.ITEM.getId(s.getItem()).toString());
                o.put("count", s.getCount());
            }
            return n;
        }));
        r.register("container.click", params -> ClientThread.call(2_000, () -> {
            int slot = params.get("slot").asInt();
            int button = params.path("button").asInt(0);
            String modeStr = params.path("mode").asText("PICKUP");
            var mc = MinecraftClient.getInstance();
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) throw new IllegalStateException("no_container");
            var p = mc.player;
            var handler = hs.getScreenHandler();
            mc.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.valueOf(modeStr), p);
            ObjectNode n = M.createObjectNode(); n.put("clicked", true); return n;
        }));
        r.register("container.close", params -> ClientThread.call(1_000, () -> {
            var mc = MinecraftClient.getInstance();
            if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
            ObjectNode n = M.createObjectNode(); n.put("closed", true); return n;
        }));
    }
}
```

**Step 9.2: Register + manual verify.** Place a chest, stand next to it, `container.open`, poll `container.state`, move an item with `container.click`, `container.close`.

**Step 9.3: Commit.**
```bash
git add . && git commit -m "c: container handlers (open/click/close/state)"
```

---

### Task 10: Baritone handlers — `goto`, `stop`, `status`, `mine`, `follow`, `build`, `setting`, `setting_get`

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/BaritoneHandlers.java`

**Step 10.1: Implement.**

```java
package com.btone.c.handlers;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.*;
import baritone.api.utils.SettingsUtil;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class BaritoneHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private BaritoneHandlers() {}

    private static IBaritone b() {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (b == null) throw new IllegalStateException("baritone_not_ready");
        return b;
    }

    public static void registerAll(RpcRouter r) {
        r.register("baritone.goto", params -> ClientThread.call(1_000, () -> {
            Goal g = parseGoal(params);
            b().getCustomGoalProcess().setGoalAndPath(g);
            ObjectNode n = M.createObjectNode(); n.put("started", true); return n;
        }));
        r.register("baritone.stop", params -> ClientThread.call(1_000, () -> {
            b().getPathingBehavior().cancelEverything();
            ObjectNode n = M.createObjectNode(); n.put("stopped", true); return n;
        }));
        r.register("baritone.status", params -> ClientThread.call(1_000, () -> {
            var pb = b().getPathingBehavior();
            ObjectNode n = M.createObjectNode();
            n.put("active", pb.isPathing());
            if (pb.getGoal() != null) n.put("goal", pb.getGoal().toString());
            return n;
        }));
        r.register("baritone.mine", params -> ClientThread.call(1_000, () -> {
            int q = params.path("quantity").asInt(-1);
            List<net.minecraft.block.Block> blocks = new ArrayList<>();
            for (JsonNode id : params.get("blocks")) {
                var block = Registries.BLOCK.get(Identifier.tryParse(id.asText()));
                blocks.add(block);
            }
            b().getMineProcess().mine(q, blocks.toArray(net.minecraft.block.Block[]::new));
            ObjectNode n = M.createObjectNode(); n.put("started", true); return n;
        }));
        r.register("baritone.follow", params -> ClientThread.call(1_000, () -> {
            String name = params.get("entityName").asText();
            b().getFollowProcess().follow(e -> e.getName().getString().equals(name));
            ObjectNode n = M.createObjectNode(); n.put("started", true); return n;
        }));
        // baritone.build left as TODO — schematic loading is its own rabbit hole; stub with explicit "not implemented" error.
        r.register("baritone.build", params -> { throw new UnsupportedOperationException("baritone.build TBD"); });

        r.register("baritone.setting", params -> ClientThread.call(1_000, () -> {
            String key = params.get("key").asText();
            var settings = BaritoneAPI.getSettings();
            var setting = settings.getByLowerName(key.toLowerCase());
            if (setting == null) throw new IllegalArgumentException("no_setting: " + key);
            JsonNode v = params.get("value");
            Object parsed = SettingsUtil.parseAndApply(settings, key, v.isTextual() ? v.asText() : v.toString());
            ObjectNode n = M.createObjectNode(); n.put("applied", true); n.put("value", String.valueOf(parsed)); return n;
        }));
        r.register("baritone.setting_get", params -> ClientThread.call(1_000, () -> {
            String key = params.get("key").asText();
            var s = BaritoneAPI.getSettings().getByLowerName(key.toLowerCase());
            if (s == null) throw new IllegalArgumentException("no_setting");
            ObjectNode n = M.createObjectNode(); n.put("key", key); n.put("value", String.valueOf(s.value)); return n;
        }));
    }

    private static Goal parseGoal(JsonNode params) {
        boolean hasX = params.has("x"), hasY = params.has("y"), hasZ = params.has("z");
        if (hasX && hasY && hasZ) return new GoalBlock(params.get("x").asInt(), params.get("y").asInt(), params.get("z").asInt());
        if (hasX && hasZ) return new GoalXZ(params.get("x").asInt(), params.get("z").asInt());
        if (hasY) return new GoalYLevel(params.get("y").asInt());
        throw new IllegalArgumentException("need_x_and_z_or_y");
    }
}
```

**Caveat:** The `SettingsUtil.parseAndApply` signature may differ in Baritone 1.15 — check the actual class. Worst case: accept only primitives (bool, int, double) and set the field directly via `setting.value = ...`.

**Step 10.2: Register + manual verify.**

```bash
# walk +50 blocks east
curl ... -d '{"method":"baritone.goto","params":{"x":100,"z":0}}'
sleep 2; curl ... -d '{"method":"baritone.status"}'
curl ... -d '{"method":"baritone.stop"}'
```

**Step 10.3: Commit.**
```bash
git add . && git commit -m "c: baritone handlers (goto/stop/status/mine/follow/setting)"
```

---

### Task 11: Event bus + `/events` SSE + game event wiring

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/events/EventBus.java`
- Create: `mod-c/src/main/java/com/btone/c/events/SseEndpoint.java`
- Create: `mod-c/src/main/java/com/btone/c/events/GameEvents.java`
- Create: `mod-c/src/test/java/com/btone/c/events/EventBusTest.java`

Same shape as Plan B Task 11+12, Java translation. The one extra concern: on the chat event handler, also call `ChatHandlers.record(msg.getString())` so `chat.recent` sees it.

**Step 11.1–11.4:** Identical pattern to B. Test `EventBus` pure. Wire:

```java
// GameEvents.java
public final class GameEvents {
    public static void register(EventBus bus) {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            String text = msg.getString();
            ChatHandlers.record(text);
            bus.emit("chat", Map.of("text", text));
        });
        ClientPlayConnectionEvents.JOIN.register((h,s,c) -> bus.emit("joined", Map.of()));
        ClientPlayConnectionEvents.DISCONNECT.register((h,c) -> bus.emit("disconnected", Map.of()));
        ClientPlayConnectionEvents.JOIN.register((h,s,c) -> registerBaritone(bus)); // defer
    }
    private static void registerBaritone(EventBus bus) {
        try {
            var b = BaritoneAPI.getProvider().getPrimaryBaritone();
            b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override public void onPathEvent(PathEvent e) { bus.emit("path", Map.of("event", e.name())); }
            });
        } catch (Throwable ignored) {}
    }
}
```

(Class names `AbstractGameEventListener` and `PathEvent` need confirmation against Baritone 1.15 JAR.)

**Step 11.5: Commit.**
```bash
git add . && git commit -m "c: event bus + /events SSE + chat/join/path wiring"
```

---

### Task 12: Meteor handlers (optional, reflection-based)

**Files:**
- Create: `mod-c/src/main/java/com/btone/c/handlers/MeteorHandlers.java`

Same reflection pattern as Plan B Task 13, Java translation. If Meteor classes load, register `meteor.modules.list`, `meteor.module.enable`, `.disable`, `.setting.get`, `.setting.set`. If not, log once and skip.

**Step 12.1: Implement + conditionally register in `BtoneC`.**

```java
try { Class.forName("meteordevelopment.meteorclient.systems.modules.Modules"); MeteorHandlers.registerAll(router); LOG.info("meteor integration enabled"); }
catch (ClassNotFoundException e) { LOG.info("meteor not present; meteor.* handlers disabled"); }
```

**Step 12.2: Manual verify** with Meteor installed: `meteor.modules.list` returns the full module list.

**Step 12.3: Commit.**
```bash
git add . && git commit -m "c: optional meteor handlers via reflection"
```

---

### Task 13: `btone-c-runner` — launcher for mcp-v8 + harness docs

**Why:** The developer needs a one-command way to start mcp-v8 pointing at the right heap dir, with instructions for the agent.

**Files:**
- Create: `btone-c-runner/start-mcp-v8.sh`
- Create: `btone-c-runner/README.md`
- Create: `btone-c-runner/examples/quick-walk.js`
- Create: `btone-c-runner/claude-desktop-config.example.json`

**Step 13.1: `start-mcp-v8.sh`.**

```bash
#!/usr/bin/env bash
set -euo pipefail
HEAP_DIR="${HEAP_DIR:-$HOME/.btone/mcp-v8-heaps}"
HTTP_PORT="${HTTP_PORT:-25700}"
mkdir -p "$HEAP_DIR"
exec mcp-v8 \
  --directory-path "$HEAP_DIR" \
  --http-port "$HTTP_PORT"
```

`chmod +x` it.

**Step 13.2: `claude-desktop-config.example.json`.**

mcp-v8 speaks stdio by default; with `--http-port` it becomes an HTTP MCP server. Depending on how the host prefers to connect:

```json
{
  "mcpServers": {
    "btone-c-v8": {
      "command": "/absolute/path/to/btone/btone-c-runner/start-mcp-v8.sh",
      "args": [],
      "env": {}
    }
  }
}
```

If the host doesn't handle long-running HTTP spawns well, drop `--http-port`; mcp-v8 will run in stdio mode and Claude Desktop will speak to it directly.

**Step 13.3: `examples/quick-walk.js`.**

```javascript
// The agent can submit this via mcp-v8 `run_js`. BTONE_TOKEN and BTONE_PORT are
// read from the connection.json that btone-mod-c writes.
const { port, token } = await readBridge();
const rpc = async (method, params = {}) => {
  const r = await fetch(`http://127.0.0.1:${port}/rpc`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ method, params }),
  });
  return r.json();
};
const state = await rpc('player.state');
console.log('player at', state.result.pos);
const goal = { x: state.result.pos.x + 30, z: state.result.pos.z + 30 };
await rpc('baritone.goto', goal);
// poll status
for (let i = 0; i < 30; i++) {
  await new Promise(r => setTimeout(r, 1000));
  const s = await rpc('baritone.status');
  console.log(s.result);
  if (!s.result.active) break;
}
return 'done';

async function readBridge() {
  // mcp-v8's fs module is policy-gated. Simpler: agent injects these from prompt.
  // For ad-hoc testing, hardcode here.
  return { port: 25591, token: 'REPLACE_WITH_TOKEN_FROM_btone-bridge.json' };
}
```

**Step 13.4: Manual verify.**

```bash
./btone-c-runner/start-mcp-v8.sh &
# From another terminal or via Claude Desktop configured to spawn it, call mcp-v8's run_js tool with the quick-walk source.
```

**Step 13.5: Commit.**
```bash
git add btone-c-runner/
git commit -m "c: mcp-v8 runner script, example JS routine, claude desktop config"
```

---

### Task 14: End-to-end agent smoke test

**Step 14.1:** Launch Prism → `btone-c-dev` → join a singleplayer world. Verify config file written at `<instance>/.minecraft/config/btone-bridge.json`.

**Step 14.2:** Start mcp-v8: `./btone-c-runner/start-mcp-v8.sh`.

**Step 14.3:** In Claude Desktop (or your own MCP client), connect to mcp-v8. Send prompt approximately: *"Using the btone HTTP API at 127.0.0.1:25591 with bearer token X, write a JS routine that walks the player 50 blocks north and reports their final position."*

Expected: agent writes a `run_js` call whose source uses `fetch()` → `/rpc player.state`, `/rpc baritone.goto`, polling `/rpc baritone.status`, and returns the final position. Minecraft client walks, agent reports.

**Step 14.4:** Scale test: ask the agent to "mine three oak logs using baritone then open the nearest chest." Watch both the mod (via `/events` SSE in another terminal) and the game.

**Step 14.5:** Commit any README/doc fixes discovered.

---

### Task 15: Polish and ship

- **15.1.** `mod-c/README.md`: full method/event catalog, curl examples, how to point mcp-v8 at it.
- **15.2.** `.gitignore`: `build/`, `.gradle/`, `run/`, `.btone/`.
- **15.3.** Release: `./gradlew build`, tag `v0.1.0-c`.

```bash
git add . && git commit -m "c: readme and polish"
git tag v0.1.0-c
```

---

## Known risks / things to watch

1. **Yarn mapping drift.** Names like `getNetworkHandler`, `getHungerManager`, `sendChatCommand` are yarn strings and occasionally move between yarn builds for the same MC version. If compile fails, open the decompiled `minecraft-mapped.jar` (via Loom's `genSources`) and adjust.
2. **Baritone API surface on 1.15.** Paths to `GoalBlock`, `GoalXZ`, `IBaritone.getCustomGoalProcess()` should be stable, but `SettingsUtil.parseAndApply` has a history of churn. Worst case: simple typed setter.
3. **Screen/container state.** `HandledScreen.getScreenHandler()` is the right surface; don't reach into the `Screen` directly.
4. **mcp-v8's `fs` policy.** By default `fs` is disabled — the agent cannot read `btone-bridge.json` unless an OPA policy allows it. Simpler path: the developer pastes port+token into the agent's prompt, or the mod exposes an unauthenticated `GET /hello` that returns `{port}` (token still required for `/rpc`). Choose based on preference; for v0.1 hardcode in prompt.
5. **Security.** Localhost-bound + bearer-auth is standard. Do **not** expose to non-loopback.

---

## File tree (final)

```
mod-c/
  build.gradle.kts  settings.gradle.kts  gradle.properties
  gradle/wrapper/...   gradlew  gradlew.bat
  libs/baritone-api-fabric-1.15.0.jar
  src/main/resources/fabric.mod.json
  src/main/java/com/btone/c/
    BtoneC.java
    ClientThread.java
    util/Token.java  util/ConnectionConfig.java
    http/Auth.java  http/BtoneHttpServer.java
    rpc/RpcHandler.java  rpc/RpcRouter.java
    events/EventBus.java  events/SseEndpoint.java  events/GameEvents.java
    handlers/PlayerHandlers.java
    handlers/WorldReadHandlers.java
    handlers/WorldWriteHandlers.java
    handlers/ChatHandlers.java
    handlers/ContainerHandlers.java
    handlers/BaritoneHandlers.java
    handlers/MeteorHandlers.java
  src/test/java/com/btone/c/
    util/TokenTest.java  util/ConnectionConfigTest.java
    http/AuthTest.java
    rpc/RpcRouterTest.java
    events/EventBusTest.java
btone-c-runner/
  start-mcp-v8.sh  README.md
  examples/quick-walk.js
  claude-desktop-config.example.json
```

Estimated effort: 3–4 focused sessions — more handlers than B, but each is mechanical.
