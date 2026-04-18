# Option B: `btone-mod-b` Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A Fabric 1.21.8 client mod that exposes one MCP tool (`eval` + `eval_status`) over Streamable HTTP. The tool compiles and runs arbitrary Kotlin source inside the Minecraft JVM, giving the agent direct typed access to `mc: MinecraftClient`, `baritone: IBaritone?`, `meteor: MeteorFacade?`, `events: EventBus`, and a `registerCleanup(...)` hook. Plus an `/events` SSE stream.

**Architecture:** Single Fabric mod JAR. In-JVM Java MCP SDK 1.0 server on `127.0.0.1:25590`. Kotlin scripting via `kotlin-scripting-jvm-host` with a custom `ScriptDefinition` that declares implicit receivers. All script bodies default-execute on the client thread via `MinecraftClient.submit { ... }.get()`; `async: true` runs on a worker thread and the script is expected to wrap its own MC access in `mc.execute {}`. Bearer token auth, file written to `config/btone-bridge.json`.

**Tech Stack:**
- Fabric Loader 0.16+, Fabric API, Java 21
- Minecraft 1.21.8
- Fabric Language Kotlin 1.13+
- Kotlin 2.0.x, Gradle 8.10, Loom 1.7+
- `baritone-api-fabric-1.15.0.jar` (vendored in `libs/`)
- `io.modelcontextprotocol.sdk:mcp:1.0.0`
- `org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.0.x`
- JDK built-in `com.sun.net.httpserver.HttpServer` for SSE
- JUnit 5 for pure-logic tests

**Testing Note (read first):** Minecraft mods cannot be meaningfully unit-tested for anything that touches `MinecraftClient`. We split the code into two zones:
- **Pure-logic zone** (token generation, path-to-goal parsing if any, event JSON serialization, script-definition construction): JUnit 5 unit tests, TDD.
- **MC-coupled zone** (every handler, every event wire-up, the scripting host executing against real MC): **integration tested manually** by running the client via Prism and hitting endpoints with `curl`. Each such task has an explicit "Manual verification" step with the exact `curl` invocation and expected output.

Do not invent unit tests for MC-coupled code by mocking `MinecraftClient`. It produces tests that pass while the mod is broken. Integration verification is the test.

**Assumed prior knowledge:** None. Each task explains what it's doing and why.

---

## Prelude: One-time environment setup

Before Task 1, the developer does these once. They are not code changes.

**P1. Install Prism Launcher.**
`brew install --cask prismlauncher` (macOS). Launch it; on first run it will walk through Microsoft account login.

**P2. Create a Minecraft 1.21.8 Fabric instance.**
In Prism: *Add Instance → Vanilla → Version 1.21.8 → Loader: Fabric → latest loader*. Name it `btone-b-dev`. Edit the instance, in *Version → Install Fabric API* (pick a 1.21.8-compatible release, e.g. `0.110.x+1.21.8`).

**P3. Install `fabric-language-kotlin`.** In the instance's *Mods* tab, *Browse → Modrinth → search "Fabric Language Kotlin" → install latest 1.21.8 build*.

**P4. Install Baritone (standalone-fabric).** Download `baritone-standalone-fabric-1.15.0.jar` from https://github.com/cabaletta/baritone/releases/tag/v1.15.0. Drop into the instance's `mods/` folder (*Folder* button in Prism → `mods/`).

**P5. Install Meteor Client (optional for B; required for Meteor integration).** Download Meteor Client for 1.21.8 from https://meteorclient.com. Drop into `mods/`.

**P6. Verify instance boots.** Launch `btone-b-dev` from Prism. Close after reaching the main menu.

**P7. Install JDK 21.** `brew install openjdk@21`. Point Prism's instance at it: *Edit Instance → Settings → Java → Java installation path*.

**P8. Confirm git + Node are present.** `git --version`, `node --version` (20+). The Node sidecar in Task 15 uses Node only.

---

### Task 1: Gradle + Loom bootstrap

> **Implementation note:** exact versions were bumped from the originally drafted plan to match live maven.fabricmc.net state as of 2026-04-18. Structure unchanged.

**Why:** Fabric mods build with Gradle using the Loom plugin. We need a project that compiles a minimal mod JAR.

**Files:**
- Create: `mod-b/settings.gradle.kts`
- Create: `mod-b/build.gradle.kts`
- Create: `mod-b/gradle.properties`
- Create: `mod-b/src/main/resources/fabric.mod.json`
- Create: `mod-b/src/main/kotlin/com/btone/b/BtoneB.kt`
- Create: `mod-b/gradle/wrapper/gradle-wrapper.properties`

**Step 1.1: Create `mod-b/gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=1.21.8
yarn_mappings=1.21.8+build.1
loader_version=0.19.2
fabric_version=0.136.1+1.21.8
fabric_kotlin_version=1.13.10+kotlin.2.3.20

mod_version=0.1.0
maven_group=com.btone
archives_base_name=btone-mod-b

kotlin_version=2.3.20
mcp_sdk_version=1.0.0
```

(Version pins chosen to match the 2026-04-18 ecosystem state. Adjust if newer Fabric API builds are available at implementation time — check Modrinth for the latest `+1.21.8` build.)

**Step 1.2: Create `mod-b/settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "btone-mod-b"
```

**Step 1.3: Create `mod-b/build.gradle.kts`**

```kotlin
plugins {
    id("fabric-loom") version "1.11.8"
    kotlin("jvm") version "2.3.20"
    `maven-publish`
}

base { archivesName = property("archives_base_name") as String }
version = property("mod_version") as String
group = property("maven_group") as String

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Baritone API — vendored JAR, compile-only (user installs standalone at runtime)
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0.jar"))

    // Java MCP SDK 1.0
    implementation("io.modelcontextprotocol.sdk:mcp:${property("mcp_sdk_version")}")

    // Kotlin scripting host
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:${property("kotlin_version")}")

    // Jackson for JSON (MCP SDK also pulls this; pin explicitly)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin { jvmToolchain(21) }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.test { useJUnitPlatform() }

loom {
    // include runtime deps inside the jar (Jar-in-Jar)
    runtimeOnlyLog4j = false
}
```

**Step 1.4: Create `mod-b/src/main/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "btone_mod_b",
  "version": "${version}",
  "name": "Btone Mod B (eval)",
  "description": "MCP eval tool backed by Kotlin scripting for Minecraft.",
  "environment": "client",
  "entrypoints": { "client": ["com.btone.b.BtoneB"] },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.21.8",
    "java": ">=21",
    "fabric-language-kotlin": ">=1.13.0",
    "fabric-api": "*"
  },
  "suggests": {
    "baritone": "*",
    "meteor-client": "*"
  }
}
```

**Step 1.5: Create `mod-b/src/main/kotlin/com/btone/b/BtoneB.kt`**

```kotlin
package com.btone.b

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class BtoneB : ClientModInitializer {
    companion object { val log = LoggerFactory.getLogger("btone-b") }
    override fun onInitializeClient() { log.info("btone-mod-b initialized") }
}
```

**Step 1.6: Vendor the Baritone JAR.**

Run:
```bash
mkdir -p mod-b/libs
curl -L -o mod-b/libs/baritone-api-fabric-1.15.0.jar \
  https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar
```

Expected: file exists, ~3MB, `file mod-b/libs/baritone-api-fabric-1.15.0.jar` says "Java archive data".

**Step 1.7: Set up Gradle wrapper.**

```bash
cd mod-b && gradle wrapper --gradle-version 8.10 --distribution-type all
```

**Step 1.8: Build.**

```bash
cd mod-b && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`. `mod-b/build/libs/btone-mod-b-0.1.0.jar` exists.

**Step 1.9: Commit.**

```bash
git add mod-b/build.gradle.kts mod-b/settings.gradle.kts mod-b/gradle.properties \
        mod-b/src/main/resources/fabric.mod.json mod-b/src/main/kotlin/ \
        mod-b/libs/ mod-b/gradle/ mod-b/gradlew mod-b/gradlew.bat
git commit -m "b: gradle + loom bootstrap, minimal ClientModInitializer"
```

**Step 1.10: Manual verification — mod loads in Prism.**

Copy the built JAR into the Prism instance: `cp build/libs/btone-mod-b-0.1.0.jar "$PRISM_INSTANCE/.minecraft/mods/"`. Launch. In the Prism console, grep for `btone-mod-b initialized`. Expected: line present.

(From here, every task ends with an equivalent "rebuild + copy jar + check logs" step when MC-coupled. We'll abbreviate as **Redeploy + verify**.)

---

### Task 2: Token generation (pure-logic, TDD)

**Why:** The HTTP server needs a bearer token. Random-secure, written atomically, refused if too short. Pure logic — test it properly.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/Token.kt`
- Create: `mod-b/src/test/kotlin/com/btone/b/TokenTest.kt`

**Step 2.1: Write failing test.**

```kotlin
// TokenTest.kt
package com.btone.b
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenTest {
    @Test fun `generates 256 bits base64url`() {
        val t = Token.generate()
        assertEquals(43, t.length, "base64url 32 bytes = 43 chars no padding")
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
    @Test fun `two generations differ`() {
        assertNotEquals(Token.generate(), Token.generate())
    }
    @Test fun `validates constant-time`() {
        val t = Token.generate()
        assertTrue(Token.matches(t, t))
        assertTrue(!Token.matches(t, "x".repeat(t.length)))
        assertTrue(!Token.matches(t, "short"))
        assertTrue(!Token.matches("", t))
    }
}
```

**Step 2.2: Run — should fail compile.**
`cd mod-b && ./gradlew test`. Expected: compile error, `Token` unresolved.

**Step 2.3: Implement.**

```kotlin
// Token.kt
package com.btone.b
import java.security.MessageDigest
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
```

**Step 2.4: Test passes.**
`./gradlew test`. Expected: 3 tests, all pass.

**Step 2.5: Commit.**
```bash
git add mod-b/src/main/kotlin/com/btone/b/Token.kt mod-b/src/test/kotlin/
git commit -m "b: constant-time token generator"
```

---

### Task 3: Connection config file (`config/btone-bridge.json`)

**Why:** The mod must publish its port + token somewhere the agent can read. Single file, JSON, atomic write. Pure logic + `FabricLoader.getInstance().configDir`.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/ConnectionConfig.kt`
- Create: `mod-b/src/test/kotlin/com/btone/b/ConnectionConfigTest.kt`

**Step 3.1: Write failing test.**

```kotlin
package com.btone.b
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
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
```

**Step 3.2: Run. Expected: compile fail.**

**Step 3.3: Implement.**

```kotlin
package com.btone.b
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ConnectionConfig(val port: Int, val token: String, val version: String) {
    fun writeTo(path: Path) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "btone-", ".tmp")
        Files.writeString(tmp, mapper.writeValueAsString(this))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()
        fun readFrom(path: Path): ConnectionConfig =
            mapper.readValue(Files.readString(path).toByteArray(), ConnectionConfig::class.java)
    }
}
```

Add to `build.gradle.kts` deps: `implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")`.

**Step 3.4: Test passes.**

**Step 3.5: Commit.**
```bash
git add .
git commit -m "b: connection config with atomic write"
```

---

### Task 4: Minimal HTTP server with bearer auth (pure-logic core, integration edges)

**Why:** Everything — MCP endpoint, SSE stream, auth — sits on top of one `HttpServer` instance. Test the auth middleware and router pure; bind is manual-verified.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/http/Auth.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/http/Server.kt`
- Create: `mod-b/src/test/kotlin/com/btone/b/http/AuthTest.kt`

**Step 4.1: Write failing test for auth middleware.**

```kotlin
package com.btone.b.http
import com.btone.b.Token
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
```

**Step 4.2: Implement `Auth.kt`.**

```kotlin
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
        val h = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value?.firstOrNull()
            ?: return AuthResult.Missing
        if (!h.startsWith("Bearer ", ignoreCase = true)) return AuthResult.BadScheme
        return if (Token.matches(expectedToken, h.substring(7).trim())) AuthResult.Ok else AuthResult.Forbidden
    }
}
```

**Step 4.3: Tests pass.**

**Step 4.4: Implement HTTP server wrapper.** (Non-TDD — MC-coupled thread, integration-verified.)

```kotlin
package com.btone.b.http
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class BtoneHttpServer(
    port: Int,
    private val token: String,
    private val routes: Map<String, (HttpExchange) -> Unit>,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    init {
        routes.forEach { (path, handler) ->
            server.createContext(path, HttpHandler { ex ->
                when (Auth.check(ex.requestHeaders, token)) {
                    AuthResult.Ok -> try { handler(ex) } catch (t: Throwable) { write(ex, 500, """{"error":"${t.message}"}""") }
                    else -> write(ex, 401, """{"error":"unauthorized"}""")
                }
            })
        }
    }
    fun actualPort(): Int = server.address.port
    fun start() { server.executor = null; server.start() }
    fun stop() { server.stop(0) }

    companion object {
        fun write(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            ex.responseHeaders["Content-Type"] = listOf(contentType)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
```

**Step 4.5: Wire into `BtoneB.kt`.**

```kotlin
class BtoneB : ClientModInitializer {
    companion object { val log = LoggerFactory.getLogger("btone-b") }
    override fun onInitializeClient() {
        val token = Token.generate()
        val server = BtoneHttpServer(port = 25590, token = token, routes = mapOf(
            "/health" to { ex -> BtoneHttpServer.write(ex, 200, """{"ok":true}""") }
        ))
        server.start()
        val cfgPath = FabricLoader.getInstance().configDir.resolve("btone-bridge.json")
        ConnectionConfig(server.actualPort(), token, "0.1.0").writeTo(cfgPath)
        log.info("btone-mod-b listening on 127.0.0.1:${server.actualPort()}; config at $cfgPath")
        // TODO: register shutdown hook via ClientLifecycleEvents.CLIENT_STOPPING to server.stop()
    }
}
```

Needs `import net.fabricmc.loader.api.FabricLoader`.

**Step 4.6: Manual verification.**

Redeploy. Launch Prism. In the log find `listening on 127.0.0.1:25590; config at …/btone-bridge.json`. Then:

```bash
TOKEN=$(jq -r .token ~/Library/Application\ Support/PrismLauncher/instances/btone-b-dev/.minecraft/config/btone-bridge.json)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25590/health
# expected: {"ok":true}
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:25590/health
# expected: 401
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer wrong" http://127.0.0.1:25590/health
# expected: 401
```

(The exact config path varies by OS. `FabricLoader.getInstance().configDir` = `<gameDir>/config`. On macOS Prism, that's `~/Library/Application Support/PrismLauncher/instances/btone-b-dev/.minecraft/config`.)

**Step 4.7: Commit.**

```bash
git add .
git commit -m "b: http server with bearer auth, /health endpoint"
```

---

### Task 5: Lifecycle — clean shutdown on CLIENT_STOPPING

**Why:** Without this, reloading the mod leaks ports and the next launch fails to bind.

**Files:**
- Modify: `mod-b/src/main/kotlin/com/btone/b/BtoneB.kt`

**Step 5.1:** In `onInitializeClient`, after `server.start()`:

```kotlin
ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
    log.info("btone-mod-b stopping, closing http server")
    server.stop()
}
```

Import: `import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents`.

**Step 5.2: Manual verify.** Launch, Ctrl-C the launcher. Relaunch. If bind fails (`Address already in use`), this task regressed. Expected: clean relaunch.

**Step 5.3: Commit.**
```bash
git add . && git commit -m "b: close http server on client stop"
```

---

### Task 6: Java MCP SDK — Streamable HTTP transport on `/mcp`

**Why:** This is what turns our HTTP server into an MCP server. The SDK handles JSON-RPC framing, init handshake, tool registration.

**References to skim** before starting: https://github.com/modelcontextprotocol/java-sdk/tree/main/mcp/src/main/java/io/modelcontextprotocol (the `server.transport` package). The Spring-free transport we want is `McpStreamableServerTransport` (check actual class name in the 1.0 release — it may be `HttpServletStreamableServerTransportProvider` and need a servlet container). If the 1.0 release *requires* a servlet container (Jetty/Tomcat), we take two routes:

- **6A (preferred):** If there is a provider that works on top of `com.sun.net.httpserver.HttpServer` (or one we write ourselves in <100 lines), use that. The MCP Streamable HTTP spec is well-defined: `POST /mcp` carries JSON-RPC, responses stream via `Content-Type: text/event-stream` when the server needs to push.
- **6B (fallback):** Embed Jetty 12 (small, ~6MB), use `HttpServletStreamableServerTransportProvider`.

Assume 6A for the plan. The Streamable HTTP spec (https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) is short enough to implement manually.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/mcp/McpTransport.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/mcp/Registry.kt`

**Step 6.1: Define tool interface.**

```kotlin
// Registry.kt
package com.btone.b.mcp
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

interface McpTool {
    val name: String
    val description: String
    val inputSchema: ObjectNode
    suspend fun call(params: JsonNode): JsonNode   // returns MCP content envelope
}

object ToolRegistry {
    private val tools = linkedMapOf<String, McpTool>()
    fun register(t: McpTool) { tools[t.name] = t }
    fun list(): Collection<McpTool> = tools.values
    fun get(name: String) = tools[name]
}
```

**Step 6.2: Implement Streamable-HTTP transport.**

A `POST /mcp` handler that:
1. Parses JSON-RPC (`jsonrpc`, `id`, `method`, `params`).
2. Dispatches `initialize`, `tools/list`, `tools/call` to our registry.
3. Returns `application/json` for single-reply methods, or opens SSE (`Content-Type: text/event-stream`) and writes frames when a call emits progress.

The skeleton:

```kotlin
// McpTransport.kt
package com.btone.b.mcp
import com.btone.b.http.BtoneHttpServer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking

class McpTransport {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun handle(ex: HttpExchange) {
        if (ex.requestMethod != "POST") { BtoneHttpServer.write(ex, 405, """{"error":"method not allowed"}"""); return }
        val req = mapper.readTree(ex.requestBody)
        val id = req["id"]
        val method = req["method"].asText()
        val params = req["params"]
        val result = when (method) {
            "initialize" -> initializeResult()
            "tools/list" -> listResult()
            "tools/call" -> runBlocking { callResult(params) }
            else -> null
        }
        val resp = mapper.createObjectNode().put("jsonrpc", "2.0").set<com.fasterxml.jackson.databind.node.ObjectNode>("id", id)
        if (result != null) resp.set<com.fasterxml.jackson.databind.node.ObjectNode>("result", result)
        else resp.putObject("error").put("code", -32601).put("message", "method not found")
        BtoneHttpServer.write(ex, 200, mapper.writeValueAsString(resp))
    }

    private fun initializeResult() = mapper.readTree("""
        {"protocolVersion":"2025-06-18","serverInfo":{"name":"btone-mod-b","version":"0.1.0"},
         "capabilities":{"tools":{"listChanged":false}}}""".trimIndent())

    private fun listResult() = mapper.createObjectNode().also { res ->
        val arr = res.putArray("tools")
        ToolRegistry.list().forEach { t ->
            arr.addObject().apply {
                put("name", t.name); put("description", t.description); set<com.fasterxml.jackson.databind.node.ObjectNode>("inputSchema", t.inputSchema)
            }
        }
    }

    private suspend fun callResult(params: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode {
        val name = params["name"].asText()
        val args = params["arguments"] ?: mapper.createObjectNode()
        val tool = ToolRegistry.get(name) ?: return mapper.createObjectNode().apply { put("isError", true); putArray("content").addObject().put("type", "text").put("text", "unknown tool $name") }
        return tool.call(args)
    }
}
```

**Step 6.3:** Register `/mcp` route in `BtoneB.kt`:

```kotlin
val transport = McpTransport()
val server = BtoneHttpServer(port = 25590, token = token, routes = mapOf(
    "/health" to { ex -> BtoneHttpServer.write(ex, 200, """{"ok":true}""") },
    "/mcp" to transport::handle,
))
```

**Step 6.4: Register a stub `echo` tool in `BtoneB.onInitializeClient` before starting the server.**

```kotlin
ToolRegistry.register(object : McpTool {
    override val name = "echo"
    override val description = "debug echo"
    override val inputSchema = transport.mapper.readTree("""{"type":"object","properties":{"msg":{"type":"string"}}}""") as com.fasterxml.jackson.databind.node.ObjectNode
    override suspend fun call(params: com.fasterxml.jackson.databind.JsonNode) =
        transport.mapper.createObjectNode().apply { putArray("content").addObject().put("type","text").put("text", params["msg"]?.asText() ?: "") }
})
```

**Step 6.5: Manual verification.** Redeploy. Then:

```bash
TOKEN=...  # as before
# initialize
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
# tools/list
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# tools/call echo
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"msg":"hello"}}}'
# expected last: {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}]}}
```

**Step 6.6: Commit.**
```bash
git add . && git commit -m "b: minimal streamable-http MCP transport with echo tool"
```

---

### Task 7: Client-thread scheduling helper

**Why:** Every MC access must go through `MinecraftClient.submit { ... }`. We want one helper so every handler looks uniform.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/ClientThread.kt`

**Step 7.1:** Write.

```kotlin
package com.btone.b
import net.minecraft.client.MinecraftClient
import java.util.concurrent.TimeUnit

object ClientThread {
    inline fun <T> call(timeoutMs: Long = 2_000, crossinline block: () -> T): T {
        val mc = MinecraftClient.getInstance()
        return mc.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
    inline fun run(crossinline block: () -> Unit) {
        MinecraftClient.getInstance().execute { block() }
    }
}
```

**Step 7.2:** No test (MC-coupled). Just compiles.

**Step 7.3: Commit.**
```bash
git add . && git commit -m "b: ClientThread helper for on-thread MC access"
```

---

### Task 8: Kotlin scripting host with implicit receivers

**Why:** This is the core of B. The agent's submitted Kotlin source needs `mc`, `baritone`, `meteor`, `events`, `registerCleanup` available without imports.

**References:** https://github.com/Kotlin/kotlin-script-examples — specifically the `jvm-embedded-host` example. The pattern is:
1. Define a script annotation class + `KotlinScript(compilationConfiguration = ...)` data class.
2. Pass `ScriptEvaluationConfiguration { implicitReceivers(...) }` with instances.
3. Call `BasicJvmScriptingHost().eval(source, compilationConfig, evalConfig)`.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/eval/ScriptDef.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/eval/EvalContext.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/eval/EvalHost.kt`
- Create: `mod-b/src/test/kotlin/com/btone/b/eval/EvalHostTest.kt`

**Step 8.1: Define the context.**

```kotlin
// EvalContext.kt
package com.btone.b.eval

// Receiver available inside every eval as `this.*` — all public fields appear as unqualified identifiers
abstract class EvalContext {
    abstract val mc: net.minecraft.client.MinecraftClient
    abstract val baritone: baritone.api.IBaritone?
    abstract val meteor: com.btone.b.meteor.MeteorFacade?
    abstract val events: com.btone.b.events.EventBus
    abstract fun registerCleanup(fn: () -> Unit)
}
```

`baritone.api.IBaritone` comes from the vendored JAR.

**Step 8.2: Define the script class.**

```kotlin
// ScriptDef.kt
package com.btone.b.eval
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.*

@KotlinScript(
    displayName = "Btone eval script",
    fileExtension = "btone.kts",
    compilationConfiguration = BtoneScriptCompilation::class,
)
abstract class BtoneScript(val ctx: EvalContext)

object BtoneScriptCompilation : ScriptCompilationConfiguration({
    implicitReceivers(EvalContext::class)
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "net.minecraft.client.MinecraftClient",
        "baritone.api.BaritoneAPI",
        "baritone.api.pathing.goals.*",
        "net.minecraft.util.math.BlockPos",
    )
})
```

**Step 8.3: Implement eval host.**

```kotlin
// EvalHost.kt
package com.btone.b.eval
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

object EvalHost {
    private val host = BasicJvmScriptingHost()
    private val compilationCfg = createJvmCompilationConfigurationFromTemplate<BtoneScript>()

    data class Result(val ok: Boolean, val value: Any?, val stdout: String, val stderr: String, val errorMsg: String?)

    fun eval(source: String, ctx: EvalContext): Result {
        val outBuf = StringBuilder(); val errBuf = StringBuilder()
        val savedOut = System.out; val savedErr = System.err
        val tee = { target: StringBuilder -> java.io.PrintStream(object : java.io.OutputStream() { override fun write(b: Int) { target.append(b.toChar()) } }, true) }
        System.setOut(tee(outBuf)); System.setErr(tee(errBuf))
        try {
            val evalCfg = ScriptEvaluationConfiguration { implicitReceivers(ctx) }
            val res = host.eval(source.toScriptSource(), compilationCfg, evalCfg)
            return when (res) {
                is ResultWithDiagnostics.Success -> {
                    val returnValue = (res.value.returnValue as? ResultValue.Value)?.value
                    Result(true, returnValue, outBuf.toString(), errBuf.toString(), null)
                }
                is ResultWithDiagnostics.Failure -> Result(false, null, outBuf.toString(), errBuf.toString(), res.reports.joinToString("\n") { it.message })
            }
        } finally { System.setOut(savedOut); System.setErr(savedErr) }
    }
}
```

**Step 8.4: Unit test (plain Kotlin source, no MC dependency).** We provide a fake `EvalContext` to prove the mechanism.

```kotlin
package com.btone.b.eval
import com.btone.b.events.EventBus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalHostTest {
    private val fakeCtx = object : EvalContext() {
        override val mc get() = error("not in test")
        override val baritone = null
        override val meteor = null
        override val events = EventBus()
        override fun registerCleanup(fn: () -> Unit) {}
    }
    @Test fun `evaluates simple expression`() {
        val r = EvalHost.eval("1 + 2", fakeCtx)
        assertTrue(r.ok, r.errorMsg ?: "")
        assertEquals(3, r.value)
    }
    @Test fun `captures stdout`() {
        val r = EvalHost.eval("""println("hi"); 42""", fakeCtx)
        assertTrue(r.ok)
        assertEquals("hi\n", r.stdout)
        assertEquals(42, r.value)
    }
    @Test fun `reports compile errors`() {
        val r = EvalHost.eval("this is not kotlin", fakeCtx)
        assertTrue(!r.ok)
        assertTrue(r.errorMsg!!.isNotEmpty())
    }
}
```

`EventBus` is stubbed later in Task 12 — create a minimal placeholder now:

```kotlin
// mod-b/src/main/kotlin/com/btone/b/events/EventBus.kt
package com.btone.b.events
class EventBus { fun emit(type: String, payload: Map<String, Any?>) {} }
```

**Step 8.5:** Run tests. Expected: all pass.

**Step 8.6: Commit.**
```bash
git add . && git commit -m "b: kotlin scripting host with implicit EvalContext receiver"
```

---

### Task 9: The `eval` MCP tool (sync mode only)

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/eval/EvalTool.kt`
- Modify: `mod-b/src/main/kotlin/com/btone/b/BtoneB.kt` (register tool, delete echo)

**Step 9.1:** Build the production `EvalContext` (reads `baritone` and `meteor` lazily).

```kotlin
// mod-b/src/main/kotlin/com/btone/b/eval/LiveEvalContext.kt
package com.btone.b.eval
import com.btone.b.events.EventBus
import net.minecraft.client.MinecraftClient

class LiveEvalContext(override val events: EventBus) : EvalContext() {
    override val mc: MinecraftClient get() = MinecraftClient.getInstance()
    override val baritone: baritone.api.IBaritone? get() = try {
        baritone.api.BaritoneAPI.getProvider().primaryBaritone
    } catch (t: Throwable) { null }
    override val meteor: com.btone.b.meteor.MeteorFacade? get() = com.btone.b.meteor.MeteorFacade.tryGet()
    private val cleanups = java.util.concurrent.ConcurrentLinkedDeque<() -> Unit>()
    override fun registerCleanup(fn: () -> Unit) { cleanups.addLast(fn) }
    fun runCleanups() { while (true) { (cleanups.pollLast() ?: break).runCatching { invoke() } } }
}
```

Stub `MeteorFacade` now:

```kotlin
// mod-b/src/main/kotlin/com/btone/b/meteor/MeteorFacade.kt
package com.btone.b.meteor
class MeteorFacade private constructor() {
    companion object { fun tryGet(): MeteorFacade? = null } // replaced in Task 14
}
```

**Step 9.2: Implement the tool.**

```kotlin
// EvalTool.kt
package com.btone.b.eval
import com.btone.b.ClientThread
import com.btone.b.mcp.McpTool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class EvalTool(private val ctxFactory: () -> LiveEvalContext) : McpTool {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    override val name = "eval"
    override val description = "Compile and run Kotlin in the Minecraft client JVM. Implicit receiver exposes: mc, baritone, meteor, events, registerCleanup. Return value of last expression (if JSON-serializable) appears in result.value."
    override val inputSchema: ObjectNode = mapper.readTree(
        """{"type":"object","required":["source"],"properties":{
              "source":{"type":"string"},
              "timeout_ms":{"type":"integer","minimum":100,"default":10000},
              "async":{"type":"boolean","default":false}}}"""
    ) as ObjectNode

    override suspend fun call(params: JsonNode): JsonNode {
        val source = params["source"].asText()
        val timeoutMs = params["timeout_ms"]?.asLong() ?: 10_000L
        val async = params["async"]?.asBoolean() ?: false
        if (async) return startAsync(source, timeoutMs)
        val ctx = ctxFactory()
        val res = ClientThread.call(timeoutMs) { EvalHost.eval(source, ctx) }
        return toMcpContent(res)
    }

    private fun toMcpContent(res: EvalHost.Result): JsonNode {
        val env = mapper.createObjectNode()
        val content = env.putArray("content").addObject()
        content.put("type", "text")
        content.put("text", mapper.writeValueAsString(mapOf(
            "ok" to res.ok, "value" to res.value, "stdout" to res.stdout, "stderr" to res.stderr, "error" to res.errorMsg
        )))
        if (!res.ok) env.put("isError", true)
        return env
    }

    private fun startAsync(source: String, timeoutMs: Long): JsonNode = EvalJobRegistry.start(source, timeoutMs, ctxFactory).let { id ->
        mapper.createObjectNode().apply {
            putArray("content").addObject().put("type", "text").put("text", """{"jobId":"$id"}""")
        }
    }
}
```

**Step 9.3:** Add `EvalJobRegistry` as a stub for now (Task 10 fills it in):

```kotlin
// EvalJobRegistry.kt
package com.btone.b.eval
object EvalJobRegistry { fun start(source: String, timeoutMs: Long, ctx: () -> LiveEvalContext): String = throw NotImplementedError("task 10") }
```

**Step 9.4:** Register in `BtoneB`:

```kotlin
// inside onInitializeClient
val eventBus = com.btone.b.events.EventBus()
ToolRegistry.register(EvalTool { LiveEvalContext(eventBus) })
```

**Step 9.5: Manual verification** (the real smoke test for B):

```bash
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{"name":"eval","arguments":{"source":"mc.player?.name?.string ?: \"no player\""}}}'
```

Expected (at the title screen): `"no player"` in `result.content[0].text`. After joining a world: your username.

```bash
# full baritone call
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"eval","arguments":{"source":"val p = mc.player ?: return@eval null; baritone?.customGoalProcess?.setGoalAndPath(GoalXZ(p.blockX + 50, p.blockZ)); \"ok\""}}}'
```

Expected, in a world with Baritone installed: `"ok"`, player starts walking +50 X.

**Step 9.6: Commit.**
```bash
git add . && git commit -m "b: eval MCP tool (sync path)"
```

---

### Task 10: Async evals and `eval_status`

**Files:**
- Modify: `mod-b/src/main/kotlin/com/btone/b/eval/EvalJobRegistry.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/eval/EvalStatusTool.kt`

**Step 10.1: Implement the registry.**

```kotlin
package com.btone.b.eval
import com.btone.b.ClientThread
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object EvalJobRegistry {
    data class Snapshot(val state: String, val value: Any?, val stdout: String, val stderr: String, val error: String?)
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "btone-eval").also { it.isDaemon = true } }
    private val jobs = ConcurrentHashMap<String, AtomicReference<Snapshot>>()

    fun start(source: String, timeoutMs: Long, ctxFactory: () -> LiveEvalContext): String {
        val id = UUID.randomUUID().toString()
        val ref = AtomicReference(Snapshot("running", null, "", "", null))
        jobs[id] = ref
        exec.submit {
            try {
                val ctx = ctxFactory()
                // note: async → we do NOT wrap in ClientThread.call; user wraps with mc.execute inside their source
                val r = EvalHost.eval(source, ctx)
                ref.set(Snapshot(if (r.ok) "done" else "error", r.value, r.stdout, r.stderr, r.errorMsg))
            } catch (t: Throwable) {
                ref.set(Snapshot("error", null, "", "", t.message))
            }
        }
        return id
    }

    fun get(id: String): Snapshot? = jobs[id]?.get()
}
```

**Step 10.2: Implement the `eval_status` tool.**

```kotlin
package com.btone.b.eval
import com.btone.b.mcp.McpTool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class EvalStatusTool : McpTool {
    private val mapper = jacksonObjectMapper()
    override val name = "eval_status"
    override val description = "Fetch status of an async eval job started with eval(async:true)."
    override val inputSchema: ObjectNode = mapper.readTree("""{"type":"object","required":["jobId"],"properties":{"jobId":{"type":"string"}}}""") as ObjectNode
    override suspend fun call(params: JsonNode): JsonNode {
        val id = params["jobId"].asText()
        val snap = EvalJobRegistry.get(id) ?: return mapper.createObjectNode().apply {
            put("isError", true); putArray("content").addObject().put("type", "text").put("text", "unknown jobId")
        }
        return mapper.createObjectNode().apply {
            putArray("content").addObject().put("type", "text").put("text", mapper.writeValueAsString(snap))
        }
    }
}
```

**Step 10.3:** Register in `BtoneB`: `ToolRegistry.register(EvalStatusTool())`.

**Step 10.4: Manual verification.**

```bash
# start async: sleep 2s then return a value
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{"name":"eval","arguments":{"async":true,"source":"Thread.sleep(2000); 7"}}}' | tee /tmp/async.json
JOBID=$(jq -r '.result.content[0].text | fromjson | .jobId' /tmp/async.json)
# poll immediately — expect running
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"eval_status\",\"arguments\":{\"jobId\":\"$JOBID\"}}}"
# wait, poll again — expect done, value=7
sleep 3
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"eval_status\",\"arguments\":{\"jobId\":\"$JOBID\"}}}"
```

**Step 10.5: Commit.**
```bash
git add . && git commit -m "b: async evals via eval_status tool"
```

---

### Task 11: EventBus + SSE on `/events`

**Why:** Agents need to observe chat, path events, joins, deaths.

**Files:**
- Modify: `mod-b/src/main/kotlin/com/btone/b/events/EventBus.kt`
- Create: `mod-b/src/main/kotlin/com/btone/b/events/SseEndpoint.kt`
- Create: `mod-b/src/test/kotlin/com/btone/b/events/EventBusTest.kt`

**Step 11.1: TDD — implement a proper EventBus.**

```kotlin
package com.btone.b.events
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import java.util.concurrent.CopyOnWriteArrayList

class EventBusTest {
    @Test fun `delivers to subscribers`() {
        val b = EventBus(); val got = CopyOnWriteArrayList<Event>()
        b.subscribe { got.add(it) }
        b.emit("x", mapOf("k" to 1))
        assertEquals(1, got.size); assertEquals("x", got[0].type); assertEquals(1, got[0].payload["k"])
    }
    @Test fun `unsubscribe stops delivery`() {
        val b = EventBus(); val got = CopyOnWriteArrayList<Event>()
        val sub = b.subscribe { got.add(it) }
        sub.close()
        b.emit("x", emptyMap())
        assertEquals(0, got.size)
    }
}
```

```kotlin
package com.btone.b.events
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

data class Event(val type: String, val ts: Long, val payload: Map<String, Any?>)

class EventBus {
    private val subs = CopyOnWriteArrayList<(Event) -> Unit>()
    fun emit(type: String, payload: Map<String, Any?>) {
        val ev = Event(type, System.currentTimeMillis(), payload)
        subs.forEach { runCatching { it(ev) } }
    }
    fun subscribe(fn: (Event) -> Unit): Closeable {
        subs.add(fn); return Closeable { subs.remove(fn) }
    }
}
```

**Step 11.2: Implement SSE endpoint.**

```kotlin
package com.btone.b.events
import com.btone.b.http.BtoneHttpServer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange

class SseEndpoint(private val bus: EventBus) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    fun handle(ex: HttpExchange) {
        ex.responseHeaders["Content-Type"] = listOf("text/event-stream")
        ex.responseHeaders["Cache-Control"] = listOf("no-cache")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val sub = bus.subscribe { ev ->
            try {
                val line = "event: ${ev.type}\ndata: ${mapper.writeValueAsString(ev)}\n\n"
                out.write(line.toByteArray()); out.flush()
            } catch (_: Throwable) { /* client gone; next loop drops sub */ }
        }
        try {
            // keep the exchange alive — no client→server reads
            while (true) { Thread.sleep(30_000); out.write(": keepalive\n\n".toByteArray()); out.flush() }
        } catch (_: Throwable) {
        } finally { sub.close(); try { out.close() } catch (_: Throwable) {} }
    }
}
```

**Step 11.3:** Wire into `BtoneB`: register `"/events"` route.

**Step 11.4: Manual verification.**

```bash
# in one terminal, open the stream
curl -N -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25590/events
# in another, fire an event via eval
curl -s -X POST http://127.0.0.1:25590/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{"name":"eval","arguments":{"source":"events.emit(\"hello\", mapOf(\"n\" to 1))"}}}'
# expected in stream: event: hello  data: {"type":"hello","ts":...,"payload":{"n":1}}
```

**Step 11.5: Commit.**
```bash
git add . && git commit -m "b: eventbus + SSE /events endpoint"
```

---

### Task 12: Wire game events into the bus

**Why:** Make `chat`, `joined`, `disconnected`, `deathScreen`, `path` actually fire.

**Files:**
- Create: `mod-b/src/main/kotlin/com/btone/b/events/GameEvents.kt`
- Modify: `mod-b/src/main/kotlin/com/btone/b/BtoneB.kt`

**Step 12.1: Implement.**

```kotlin
package com.btone.b.events
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

object GameEvents {
    fun register(bus: EventBus) {
        ClientReceiveMessageEvents.GAME.register { msg, _ ->
            bus.emit("chat", mapOf("text" to msg.string))
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> bus.emit("joined", emptyMap()) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> bus.emit("disconnected", emptyMap()) }
        registerBaritonePathListener(bus)
    }
    private fun registerBaritonePathListener(bus: EventBus) {
        try {
            val b = baritone.api.BaritoneAPI.getProvider().primaryBaritone ?: return
            b.gameEventHandler.registerEventListener(object : baritone.api.event.listener.AbstractGameEventListener() {
                override fun onPathEvent(event: baritone.api.event.events.PathEvent) {
                    bus.emit("path", mapOf("event" to event.name))
                }
            })
        } catch (_: Throwable) { /* baritone absent */ }
    }
}
```

Note: the exact `AbstractGameEventListener` subclass lives in `baritone.api.event.listener` — confirm by inspecting the vendored JAR: `unzip -p mod-b/libs/baritone-api-fabric-1.15.0.jar META-INF/MANIFEST.MF` and `jar tf` to find class names. Adjust imports to match actual Baritone 1.15 package structure.

Baritone's listener must be registered *after* the player joins a world — `baritone.api.BaritoneAPI.getProvider().primaryBaritone` is `null` at init. Fix: call `registerBaritonePathListener` on `ClientPlayConnectionEvents.JOIN` instead of at startup.

**Step 12.2:** Call `GameEvents.register(eventBus)` in `BtoneB.onInitializeClient`.

**Step 12.3: Manual verification.** Join a world, type in chat, walk into lava, run `#goto 100 100` via in-game chat (Baritone command). Watch the SSE stream pick up `chat`, `path`, `deathScreen` (TBD — may need a `HudRenderEvents`-adjacent hook; document if not trivially available).

**Step 12.4: Commit.**
```bash
git add . && git commit -m "b: wire chat / join / disconnect / path into eventbus"
```

---

### Task 13: Meteor integration (optional)

**Why:** If Meteor Client is installed, light up `meteor` receiver with a small surface (list modules, enable/disable, read/write settings).

**Files:**
- Modify: `mod-b/src/main/kotlin/com/btone/b/meteor/MeteorFacade.kt`

**Step 13.1: Implement with reflection.** We use reflection so the mod loads without Meteor.

```kotlin
package com.btone.b.meteor

class MeteorFacade private constructor(private val modulesClass: Class<*>, private val modulesInstance: Any) {
    fun list(): List<String> {
        val getAll = modulesClass.getMethod("getAll")
        @Suppress("UNCHECKED_CAST")
        val modules = getAll.invoke(modulesInstance) as Collection<Any>
        return modules.map { m -> m.javaClass.getMethod("getName").invoke(m) as String }
    }
    fun toggle(name: String, enable: Boolean?) {
        val get = modulesClass.getMethod("get", String::class.java)
        val m = get.invoke(modulesInstance, name) ?: throw IllegalArgumentException("no module $name")
        if (enable == null) m.javaClass.getMethod("toggle").invoke(m)
        else {
            val current = m.javaClass.getMethod("isActive").invoke(m) as Boolean
            if (current != enable) m.javaClass.getMethod("toggle").invoke(m)
        }
    }

    companion object {
        fun tryGet(): MeteorFacade? = try {
            val cls = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules")
            val inst = cls.getMethod("get").invoke(null)
            MeteorFacade(cls, inst)
        } catch (_: Throwable) { null }
    }
}
```

**Step 13.2: Manual verification.** With Meteor installed, eval:

```kotlin
meteor?.list()
```

Expected: list of module name strings.

```kotlin
meteor?.toggle("NoFall", true); "toggled"
```

Expected: module enabled in Meteor's GUI (`Right Shift` to open).

**Step 13.3:** If Meteor's actual classes/method names differ in the installed version, fix via reflection-probing: decompile `meteor-client-*.jar` (IntelliJ will do this) and confirm `meteordevelopment.meteorclient.systems.modules.Modules` and method names. Fix imports in `MeteorFacade`.

**Step 13.4: Commit.**
```bash
git add . && git commit -m "b: meteor integration via reflection"
```

---

### Task 14: Stdio proxy (Node)

**Why:** Some MCP hosts only speak stdio. We ship a tiny Node proxy that reads JSON-RPC from stdin, forwards over HTTP Streamable, writes replies to stdout.

**Files:**
- Create: `stdio-proxy/package.json`
- Create: `stdio-proxy/index.mjs`
- Create: `stdio-proxy/README.md`

**Step 14.1:** Create `stdio-proxy/package.json`:

```json
{
  "name": "@btone/mcp-stdio-proxy",
  "version": "0.1.0",
  "type": "module",
  "bin": { "btone-mcp-stdio": "./index.mjs" },
  "engines": { "node": ">=20" }
}
```

**Step 14.2: Implement the proxy.**

```javascript
#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const cfgPath = process.env.BTONE_CONFIG
  ?? findConfig();
const { port, token } = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
const base = `http://127.0.0.1:${port}/mcp`;

function findConfig() {
  // macOS Prism default
  const candidates = [
    path.join(os.homedir(), 'Library/Application Support/PrismLauncher/instances'),
  ];
  for (const root of candidates) {
    if (!fs.existsSync(root)) continue;
    for (const inst of fs.readdirSync(root)) {
      const c = path.join(root, inst, '.minecraft/config/btone-bridge.json');
      if (fs.existsSync(c)) return c;
    }
  }
  throw new Error('btone-bridge.json not found; set BTONE_CONFIG');
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) !== -1) {
    const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    forward(line);
  }
});

async function forward(line) {
  const r = await fetch(base, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: line,
  });
  const text = await r.text();
  process.stdout.write(text + '\n');
}
```

**Step 14.3: Manual verification.**

```bash
chmod +x stdio-proxy/index.mjs
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | stdio-proxy/index.mjs
# expected: one-line JSON response matching the /mcp HTTP reply.
```

**Step 14.4: Commit.**
```bash
git add stdio-proxy/
git commit -m "b: stdio→streamable-http proxy for MCP hosts that need stdio"
```

---

### Task 15: End-to-end agent smoke test

**Why:** Prove the full loop: MCP host → tool/call eval → Kotlin in JVM → Baritone → player moves.

**Step 15.1: Add MCP server to Claude Desktop config.**

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "btone-b": {
      "command": "node",
      "args": ["/absolute/path/to/btone/stdio-proxy/index.mjs"]
    }
  }
}
```

Restart Claude Desktop. Open a new chat. Confirm "btone-b" appears in the 🔌 menu with tools `eval`, `eval_status`.

**Step 15.2: Scripted test.** Launch Minecraft (Prism, btone-b-dev instance), log into a singleplayer world. In Claude Desktop:

> Call `eval` with source:
> `val p = mc.player!!; baritone!!.customGoalProcess.setGoalAndPath(baritone.api.pathing.goals.GoalXZ(p.blockX + 30, p.blockZ + 30)); "walking"`

Expected: player moves. Response: `{"ok":true,"value":"walking", ...}`.

**Step 15.3: If it fails:** inspect with (in order) (a) Prism log tab for stack traces, (b) `curl -s /mcp` directly to rule out the proxy, (c) run the same source inline in a simple eval test to rule out scripting host.

**Step 15.4: Commit docs.**
Create `mod-b/README.md` documenting ports, config path, how to use Claude Desktop. Commit.

---

### Task 16: Polish and ship

- **16.1.** `gitignore` covers `build/`, `.gradle/`, `run/`, `node_modules/`.
- **16.2.** Build release JAR: `./gradlew build --no-daemon`. Attach to a `v0.1.0` tag.
- **16.3.** Document the `async: true` contract clearly in `mod-b/README.md` — the agent must wrap MC access in `mc.execute { ... }` inside async sources.
- **16.4.** Final commit + tag.

```bash
git add . && git commit -m "b: readme + gitignore"
git tag v0.1.0-b
```

---

## Known risks / things to watch during implementation

1. **Kotlin scripting classloader.** `dependenciesFromCurrentContext(wholeClasspath = true)` is the brute-force option; if it breaks under Fabric's classloader (Knot), drop to `dependenciesFromClassContext(BtoneB::class, wholeClasspath = true)` and pass specific classes.
2. **MCP Java SDK 1.0 transport options.** If 1.0 requires Jakarta Servlet, we either add Jetty (6MB) or write the Streamable HTTP transport ourselves in 100 LOC — doable, spec is tight.
3. **Baritone API class names on 1.21.8.** Baritone upstream occasionally reshuffles. If compile fails on `baritone.api.event.*`, dump class list from the JAR and fix imports.
4. **Threading semantics of scripts.** Default is on-client-thread; long blocking code (sleeps, chained pathing) will freeze the game. Agents should use `async: true` for anything that might take >100ms.
5. **Meteor reflection.** Class names may differ by Meteor release. Keep it inside `MeteorFacade.tryGet` so it can be updated without touching anything else.
6. **Hot reload.** Not planned for v0.1 — `/eval` is single-shot, no persistent script registry. Agent stores its "library" in its own memory / files.

---

## File tree (final)

```
mod-b/
  build.gradle.kts  settings.gradle.kts  gradle.properties
  gradle/wrapper/...   gradlew  gradlew.bat
  libs/baritone-api-fabric-1.15.0.jar
  src/main/resources/fabric.mod.json
  src/main/kotlin/com/btone/b/
    BtoneB.kt
    ClientThread.kt
    Token.kt
    ConnectionConfig.kt
    http/Auth.kt
    http/Server.kt
    mcp/Registry.kt
    mcp/McpTransport.kt
    events/EventBus.kt
    events/SseEndpoint.kt
    events/GameEvents.kt
    eval/EvalContext.kt
    eval/LiveEvalContext.kt
    eval/ScriptDef.kt
    eval/EvalHost.kt
    eval/EvalTool.kt
    eval/EvalJobRegistry.kt
    eval/EvalStatusTool.kt
    meteor/MeteorFacade.kt
  src/test/kotlin/com/btone/b/
    TokenTest.kt  ConnectionConfigTest.kt
    http/AuthTest.kt
    events/EventBusTest.kt
    eval/EvalHostTest.kt
stdio-proxy/
  package.json  index.mjs  README.md
```

Estimated effort: 2–3 focused sessions. The slowest moments are (a) debugging the Kotlin scripting classpath under Fabric and (b) chasing whatever the actual Baritone 1.15 class names are.
