# btone-mod-c

Fabric 1.21.8 client mod that exposes a fixed, primitive RPC surface over
loopback HTTP. Pure Java, JDK built-in `HttpServer`, no scripting in the JVM.

The agent loop happens **outside** the JVM: it writes JavaScript that runs
inside [`mcp-v8`](https://github.com/r33drichards/mcp-js) and `fetch()`es
this mod's `/rpc` endpoint. mcp-v8 is the MCP server the host (Claude
Desktop / your client) talks to. This mod is plain HTTP.

Compared to `mod-b` (Kotlin scripting in the JVM): smaller JAR (~2 MB vs
~55 MB), no Kotlin compiler embedded, no per-call compile cost, no
client-thread starvation under repeated calls. Cost: a fixed surface
instead of arbitrary code.

## Requirements

- Prism (or any Fabric-aware launcher) with a 1.21.8 instance.
- Fabric Loader >= 0.16, Fabric API for 1.21.8.
- JDK 21 (the repo's Nix flake provides one).
- **Required:** `baritone-api-fabric-1.15.0.jar` in `mods/`. Use the
  **API** variant, not standalone -- standalone obfuscates `baritone.api.*`
  classes and integrations break at runtime.
- Optional: Meteor Client (the `meteor.*` handlers are reflection-loaded
  and only registered if Meteor is on the classpath at init).

## Build

From `mod-c/`:

```bash
nix develop .. --command ./gradlew build
```

Output: `build/libs/btone-mod-c-0.1.0.jar` (~2 MB; only Jackson is bundled
via Fabric's Jar-in-Jar, no Kotlin / scripting runtime).

## Install

```bash
cp build/libs/btone-mod-c-0.1.0.jar \
  ~/Library/Application\ Support/PrismLauncher/instances/<instance>/.minecraft/mods/
```

On client init the mod writes:

```
<instance>/.minecraft/config/btone-bridge.json
```

```json
{ "port": 25591, "token": "<43-char base64url>", "version": "0.1.0" }
```

The token is regenerated every launch. The mod does NOT bind to anything
other than `127.0.0.1`.

## Endpoints

All on `127.0.0.1:25591` by default.

| Path | Method | Auth | Purpose |
| --- | --- | --- | --- |
| `/health` | GET | bearer | liveness; returns `{"ok":true}` |
| `/rpc` | POST | bearer | `{method, params}` -> `{ok, result}` or `{ok:false, error}` |
| `/events` | GET | bearer | SSE stream of game events |

All routes require `Authorization: Bearer <token>`. The auth check is
constant-time. Failure returns `401 {"error":"unauthorized"}` before the
route handler runs.

### `POST /rpc`

```json
{ "method": "player.state", "params": {} }
```

Response:

```json
{ "ok": true, "result": { ... } }
{ "ok": false, "error": { "code": "...", "message": "..." } }
```

`debug.echo` returns its `params`. `debug.methods` returns
`{methods:[...]}` so the agent can self-discover. Unknown methods return
`{ok:false, error:{code:"unknown_method", message:"<method>"}}`.

### Method catalog

| Method | Params | Notes |
| --- | --- | --- |
| `player.state` | -- | pos / blockPos / rot / health / food / dim / name |
| `player.inventory` | -- | hotbarSlot + non-empty main slots |
| `player.equipped` | -- | mainHand / offHand id+count+empty |
| `world.block_at` | `{x,y,z}` | `{id, air}` |
| `world.blocks_around` | `{radius?}` | radius capped at 8 |
| `world.raycast` | `{max?}` | client raycast |
| `chat.send` | `{text}` | auto-detects `/commands` |
| `chat.recent` | `{n?}` | rolling 256-line buffer |
| `world.mine_block` | `{x,y,z}` | single-tick attack start; poll/repeat for survival |
| `world.place_block` | `{x,y,z,hand?}` | aims at target then `interactBlock` |
| `world.use_item` | `{hand?}` | `interactItem` |
| `world.interact_entity` | `{entityId,hand?}` | `interactEntity` |
| `container.open` | `{x,y,z}` | fire-and-poll; check `container.state` |
| `container.state` | -- | open / screen / syncId / non-empty slots |
| `container.click` | `{slot,button?,mode?}` | `mode` is a `SlotActionType` name |
| `container.close` | -- | -- |
| `baritone.goto` | `{x,z}`, `{x,y,z}`, or `{y}` | -- |
| `baritone.stop` | -- | -- |
| `baritone.status` | -- | hasBaritone / active / goal |
| `baritone.mine` | `{blocks:[id], quantity?}` | unknown ids skipped |
| `baritone.follow` | `{entityName}` | -- |
| `baritone.setting` | `{key, value}` | uses `SettingsUtil.parseAndApply` |
| `baritone.setting_get` | `{key}` | -- |
| `meteor.modules.list` | -- | only if Meteor present |
| `meteor.module.{enable,disable,toggle}` | `{name}` | -- |
| `meteor.module.is_active` | `{name}` | -- |

`baritone.build` is intentionally not implemented in v0.1 (schematic loading
is its own rabbit hole).

### `GET /events` (SSE)

Each event is one SSE message:

```
event: <type>
data: { "type": "...", "ts": <epoch-ms>, "payload": { ... } }
```

| Type | Payload |
| --- | --- |
| `chat` | `{ text, overlay }` |
| `joined` | `{}` |
| `disconnected` | `{}` |
| `path` | `{ event: "<Baritone PathEvent name>" }` (after JOIN, requires Baritone) |

Heartbeat: `: keepalive` comment every 30s.

## Smoke test

See [`SMOKE.md`](SMOKE.md).

## License

MIT.
