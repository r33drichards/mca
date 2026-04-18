# btone-mod-b

Fabric 1.21.8 client mod that exposes a single MCP tool, `eval`, plus an
`eval_status` tool for async jobs and an SSE `/events` stream. The agent
writes Kotlin; the mod compiles and runs it inside the Minecraft JVM with
implicit access to `mc`, `baritone`, `meteor`, `events`, and a
`registerCleanup` hook. Maximum-power option: anything reachable from the
Minecraft classloader is reachable from a script.

## Requirements

- Prism Launcher (or any Fabric-aware launcher) with a 1.21.8 instance.
- Fabric Loader >= 0.16, Fabric API for 1.21.8, Fabric Language Kotlin.
- JDK 21 (the repo's Nix flake provides one).
- **Required for movement tools:** `baritone-api-fabric-1.15.0.jar` in `mods/`.
  Use the **API** variant, not standalone — standalone obfuscates `baritone.api.*`
  classes and integrations break at runtime.
- Optional: Meteor Client (`MeteorFacade.tryGet()` returns null otherwise).

## Build

From `mod-b/`:

```bash
nix develop .. --command ./gradlew build
```

Output: `build/libs/btone-mod-b-0.1.0.jar` (~55 MB; Kotlin scripting jars are
bundled via Fabric's Jar-in-Jar).

## Install

Copy the JAR to your instance's `mods/` directory:

```bash
cp build/libs/btone-mod-b-0.1.0.jar \
  ~/Library/Application\ Support/PrismLauncher/instances/<instance>/.minecraft/mods/
```

On client init the mod writes a config file containing the listening port
and a freshly-generated bearer token to:

```
<instance>/.minecraft/config/btone-bridge.json
```

```json
{ "port": 25590, "token": "...", "version": "0.1.0" }
```

The token is regenerated every launch; clients should re-read the file.

## Endpoints

All on `127.0.0.1:25590` by default.

| Path | Auth | Purpose |
| --- | --- | --- |
| `GET /health` | none | liveness; returns `{"ok":true}` |
| `POST /mcp` | `Authorization: Bearer <token>` | MCP Streamable-HTTP transport |
| `GET /events` | `Authorization: Bearer <token>` | SSE stream of game events |

## Tool: `eval`

Compiles and runs a Kotlin source string against an `EvalContext` implicit
receiver. Input schema:

```json
{
  "type": "object",
  "required": ["source"],
  "properties": {
    "source":     { "type": "string" },
    "timeout_ms": { "type": "integer", "default": 10000 },
    "async":      { "type": "boolean", "default": false }
  }
}
```

### Sync (`async: false`, default)

The mod marshals the script onto the Minecraft client thread, runs it with
the given timeout, and returns the result. The body of `source` is wrapped
inside a `eval { ... }`-style block, so `return@eval value` is valid.
Response payload:

```json
{ "ok": true, "value": <last-expr>, "stdout": "", "stderr": "", "error": null }
```

Example — read the player's coords:

```json
{
  "name": "eval",
  "arguments": {
    "source": "val p = mc.player ?: return@eval null; \"${p.blockX},${p.blockY},${p.blockZ}\""
  }
}
```

### Async (`async: true`)

The mod starts the script on a worker thread and returns immediately with
`{"jobId":"..."}`. Poll with `eval_status`. **You are not on the client
thread**; any Minecraft access must be wrapped in `mc.execute { ... }`
yourself, e.g.:

```kotlin
mc.execute {
    val p = mc.player ?: return@execute
    baritone?.customGoalProcess?.setGoalAndPath(
        baritone.api.pathing.goals.GoalXZ(p.blockX + 30, p.blockZ + 30)
    )
}
"started"
```

Async sources do not currently honour `timeout_ms`; the script is
responsible for terminating itself.

## Tool: `eval_status`

Fetches the latest snapshot of an async job. Input schema:

```json
{ "type": "object", "required": ["jobId"], "properties": { "jobId": { "type": "string" } } }
```

Output payload:

```json
{
  "state":  "running" | "ok" | "error" | "timeout",
  "value":  <last-expr-or-null>,
  "stdout": "",
  "stderr": "",
  "error":  null | "<message>"
}
```

## Implicit receivers (inside `eval`)

Available unqualified inside every `eval` source:

- `mc: net.minecraft.client.MinecraftClient`
- `baritone: baritone.api.IBaritone?` — null when Baritone isn't installed.
- `meteor: com.btone.b.meteor.MeteorFacade?` — null when Meteor isn't on the
  classpath. The facade itself is reflection-only.
- `events: com.btone.b.events.EventBus` — call `events.subscribe { ev -> ... }`
  to listen; the returned `Closeable` is yours to close (or pass to
  `registerCleanup`).
- `registerCleanup(fn: () -> Unit)` — runs at the end of the eval lifecycle.

## SSE: `/events`

Each game event is one SSE message: `event: <type>\ndata: <json>\n\n`.
Event payload shape: `{ "type": string, "ts": <epoch-ms>, "payload": {...} }`.

Currently emitted:

| Type | Payload |
| --- | --- |
| `chat` | `{ "text": "<rendered chat string>" }` |
| `joined` | `{}` (fired when the client joins a world) |
| `disconnected` | `{}` |
| `path` | `{ "event": "<Baritone PathEvent name>" }` (only after a join, only with Baritone installed) |

Heartbeat: `: keepalive` comment every 30s.

## Smoke test

See [`SMOKE.md`](SMOKE.md) for the runbook from a clean checkout to a
Claude-driven Baritone walk.

## Known limitations (verified by 2026-04-18 production smoke test)

- **Each `eval` triggers a Kotlin compile (~3s, ~50MB Kotlin compiler-embeddable
  loaded).** First call is the worst; subsequent calls reuse the host but still
  pay per-source compile cost. Under chunk-load or Baritone init pressure the
  client thread can starve and the game freezes (audio drops, render thread
  stops). Suitable for occasional reads/inspection. NOT suitable for tight
  control loops — use Option C (data-pipe + mcp-v8) for sustained control.
- **Sync evals block the client thread.** The `eval` tool wraps the entire
  script body in `MinecraftClient.submit { }.get(timeout_ms)`. Default
  `timeout_ms = 10000`. If MC is busy, you'll get
  `eval dispatch failed: TimeoutException`. Bump `timeout_ms` for slow ops or
  use `async: true`.
- **`async: true` has a known reflection bug** under the current scripting
  host — implicit-receiver bind on a worker thread can throw
  `java.lang.IllegalArgumentException: argument type mismatch`. Sync mode is
  the only fully working path today.
- **Production Fabric ships intermediary class names** (e.g. `mc::class.java.name`
  prints `net.minecraft.class_310`). The curated `BtoneApi` is the supported
  way to read MC state — its methods are compiled in our mod and Loom-remapped
  for you. Direct `mc.player.name` from a script does NOT work; only `mc as Any`
  + `BtoneApi.callYarn(...)` reflection does.
- **First `eval` call is ~3s** (compiler warmup); subsequent same-source calls
  are sub-100ms.
- `async: true` evals do not enforce `timeout_ms`. The script must terminate
  itself.
- No persistent script registry / hot reload. Each `eval` is independent;
  the agent stores its "library" in its own memory or files.
- Meteor integration is reflection-only and depends on the field/method names
  of a specific Meteor build. We use the public `name` field; if a future
  Meteor release renames it, update `meteor/MeteorFacade.kt`.
- Bearer tokens are per-launch; restart the client and clients must re-read
  `btone-bridge.json`.

## License

MIT.
