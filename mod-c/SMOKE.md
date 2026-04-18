# btone-mod-c end-to-end smoke test

A runbook to take the mod from a fresh checkout to a Claude-driven Baritone
walk via mcp-v8. Manual: you launch Minecraft, the runbook tells you what
to verify.

## 1. Prereqs

- Prism Launcher with an instance named `btone-c-dev` (or reuse
  `btone-b-dev` -- but do NOT run B and C at the same time, both bind a
  port and B is on `25590` while C is on `25591`).
- The instance has these mods in `mods/`:
  - Fabric API (matching 1.21.8)
  - Baritone API: `baritone-api-fabric-1.15.0.jar` (NOT standalone)
  - Optionally Meteor Client
- JDK 21 on PATH (the Nix flake at the repo root provides this).
- `jq`, `curl` for the verification commands below.
- `mcp-v8` installed (see `btone-c-runner/README.md`) for the agent loop.

## 2. Build & install

From the repo root:

```bash
cd /Users/robertwendt/btone
nix develop --command true                       # warm the dev shell
cd mod-c
nix develop .. --command ./gradlew build         # ~2 MB JAR
cp build/libs/btone-mod-c-0.1.0.jar \
  ~/Library/Application\ Support/PrismLauncher/instances/btone-c-dev/.minecraft/mods/
```

Launch the `btone-c-dev` instance from Prism. Wait for the title screen.

In the Prism log tab you should see:

```
[btone-c/INFO] btone-mod-c listening on 127.0.0.1:25591; config at .../config/btone-bridge.json
```

If Meteor is installed, also:

```
[btone-c/INFO] meteor integration enabled
```

## 3. Verify the bridge is up

```bash
TOKEN=$(jq -r .token \
  ~/Library/Application\ Support/PrismLauncher/instances/btone-c-dev/.minecraft/config/btone-bridge.json)
echo "$TOKEN"

# Health probe (still requires bearer auth -- everything does):
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25591/health
# expected: {"ok":true}

# Echo:
curl -s -X POST http://127.0.0.1:25591/rpc \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"debug.echo","params":{"x":1}}'
# expected: {"ok":true,"result":{"x":1}}

# Method catalog:
curl -s -X POST http://127.0.0.1:25591/rpc \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"debug.methods"}' | jq '.result.methods | length'
# expected: ~25
```

## 4. In-world handlers

Join a singleplayer world (or a server you have access to). Then:

```bash
# Player state:
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"player.state"}' | jq

# Inventory:
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"player.inventory"}' | jq

# Look at something and raycast:
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"world.raycast","params":{"max":8}}' | jq

# Send a chat:
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"chat.send","params":{"text":"hello from btone-c"}}'
```

## 5. Baritone walk

```bash
# Walk +30 blocks east:
PX=$(curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"method":"player.state"}' | jq '.result.blockPos.x')
PZ=$(curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"method":"player.state"}' | jq '.result.blockPos.z')
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"method\":\"baritone.goto\",\"params\":{\"x\":$((PX+30)),\"z\":$PZ}}"

# Watch the player walk in-game; poll status:
sleep 5
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"baritone.status"}' | jq

# Stop:
curl -s -X POST http://127.0.0.1:25591/rpc -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"baritone.stop"}'
```

## 6. SSE event stream

In a second terminal:

```bash
curl -N -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25591/events
# Walk around, type in chat, etc. You should see chat / joined / path events
# stream as `event: <type>\ndata: {...}` blocks, plus a `: keepalive` line
# every 30s.
```

## 7. mcp-v8 + agent loop

```bash
# Start mcp-v8 (separate from Minecraft):
./btone-c-runner/start-mcp-v8.sh
```

Then point your MCP host (Claude Desktop, etc.) at it -- see
`btone-c-runner/README.md` for the host-side config.

In your client, prompt the agent:

> You have an mcp-v8 tool. The btone-mod-c HTTP API is at
> http://127.0.0.1:25591/rpc with bearer token <PASTE TOKEN>.
> Wire format: POST {"method","params"} -> {"ok","result"}.
> Walk the player 30 blocks north and report the final position.

The agent should call `run_js` with a script that posts `player.state`,
`baritone.goto`, polls `baritone.status`, and returns the final position
(`btone-c-runner/examples/quick-walk.js` is a representative shape).

## 8. Failure modes & first debug steps

| Symptom | Likely cause | First check |
| --- | --- | --- |
| `curl /health` 401 | Wrong / stale `$TOKEN` | Re-extract from `btone-bridge.json`; tokens regenerate every launch |
| `curl /health` refused | Mod didn't load, port conflict (mod-b on 25590, mod-c on 25591) | Prism log tab; `lsof -i :25591` |
| `unknown_method` for everything | Handlers didn't register (probably an exception during init) | Look for `failed to start btone-mod-c http server` in the log |
| `baritone.*` returns `baritone_not_loaded` | Baritone not in `mods/`, OR the **standalone** JAR is installed instead of the **api** variant | Replace with `baritone-api-fabric-1.15.0.jar` |
| `meteor.*` returns `meteor_not_available` | Meteor not in `mods/`, or its initialization hadn't finished when our handler was called | Restart MC; the facade re-resolves on every call so just retry |
| `path` events never fire on `/events` | Baritone listener registered before player joined a world, or no Baritone | Disconnect, rejoin; `GameEvents` re-registers on each `JOIN` |
| `world.mine_block` returns `started:true` but no break | Block too far, line of sight blocked, or survival mining requires multi-tick | Approach the block; for survival, repeat the call each tick |
