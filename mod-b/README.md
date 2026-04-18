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
- Optional: `baritone-standalone-fabric-1.15.0.jar` in `mods/` (required for
  movement tools — `baritone` is null otherwise).
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

## Known limitations

- First `eval` call is ~3s (Kotlin scripting host warmup); subsequent calls
  are sub-100ms.
- `async: true` evals do not enforce `timeout_ms`. The script must terminate
  itself.
- No persistent script registry / hot reload. Each `eval` is independent;
  the agent stores its "library" in its own memory or files.
- No chat buffer or replay — subscribers only see events that arrive after
  they connect (Option C provides a buffered stream).
- Meteor integration is reflection-only and depends on the package/class
  names of a specific Meteor build. If your Meteor version differs, expect
  `meteor` to still resolve but individual calls inside the facade may
  return null. Update `meteor/MeteorFacade.kt` to match.
- Bearer tokens are per-launch; restart the client and clients must re-read
  `btone-bridge.json`.

## License

MIT.
