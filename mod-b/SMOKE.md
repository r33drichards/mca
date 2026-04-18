# btone-mod-b end-to-end smoke test

A runbook to take the mod from a fresh checkout to a Claude-driven Baritone
walk. Manual: you launch Minecraft, the runbook tells you what to verify.

## 1. Prereqs

- Prism Launcher with an instance named `btone-b-dev` (Minecraft 1.21.8,
  Fabric Loader >= 0.16).
- The instance has these mods in `mods/`:
  - Fabric API (matching 1.21.8)
  - Fabric Language Kotlin
  - Baritone standalone (`baritone-standalone-fabric-1.15.0.jar`)
  - Optionally Meteor Client (for the Meteor reflection facade)
- JDK 21 on PATH (the Nix flake at the repo root provides this).
- `jq`, `curl`, `node` >= 20 for the verification commands below.

## 2. Build & install

From the repo root:

```bash
cd /Users/robertwendt/btone
nix develop --command true                       # warm the dev shell
cd mod-b
nix develop .. --command ./gradlew build         # ~55MB JiJ-bundled JAR
cp build/libs/btone-mod-b-0.1.0.jar \
  ~/Library/Application\ Support/PrismLauncher/instances/btone-b-dev/.minecraft/mods/
```

Launch the `btone-b-dev` instance from Prism. Log in to your Mojang account.
Wait for the title screen.

In the Prism log tab you should see:

```
[btone-b/INFO] btone-mod-b listening on 127.0.0.1:25590; config at .../config/btone-bridge.json
```

## 3. Verify the bridge is up

Pull the bearer token from the config the mod just wrote:

```bash
TOKEN=$(jq -r .token \
  ~/Library/Application\ Support/PrismLauncher/instances/btone-b-dev/.minecraft/config/btone-bridge.json)
echo "$TOKEN"
```

Health probe (no auth required):

```bash
curl -s http://127.0.0.1:25590/health
# expected: {"ok":true}
```

MCP `initialize`:

```bash
curl -s -X POST http://127.0.0.1:25590/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

MCP `tools/list` (should include `eval` and `eval_status`):

```bash
curl -s -X POST http://127.0.0.1:25590/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Smoke `tools/call` against `eval` with a value-only expression
(no MC access needed yet, just proves the scripting host is up):

```bash
curl -s -X POST http://127.0.0.1:25590/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":3,"method":"tools/call",
    "params":{"name":"eval","arguments":{"source":"1 + 2"}}
  }'
# expected: a content[0].text JSON containing "ok":true and "value":3
```

The first eval call takes ~3s (Kotlin scripting host warmup); subsequent
calls are sub-100ms.

## 4. End-to-end eval call (drives the player)

Join a singleplayer world, OR connect to a test server:

```
centerbeam.proxy.rlwy.net:40387
```

Once you're in-world and can see your character, kick off Baritone:

```bash
curl -s -X POST http://127.0.0.1:25590/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":4,"method":"tools/call",
    "params":{"name":"eval","arguments":{"source":"val p = mc.player ?: return@eval null; baritone?.customGoalProcess?.setGoalAndPath(baritone.api.pathing.goals.GoalXZ(p.blockX + 30, p.blockZ + 30)); \"walking\""}}
  }'
```

Expected: response `content[0].text` parses to `{"ok":true,"value":"walking",...}`,
and the player begins pathing toward (x+30, z+30) in-game.

To stop:

```bash
curl -s -X POST http://127.0.0.1:25590/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call",
       "params":{"name":"eval","arguments":{"source":"baritone?.pathingBehavior?.cancelEverything(); \"stopped\""}}}'
```

## 5. Claude Desktop integration

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "btone-b": {
      "command": "node",
      "args": ["/Users/robertwendt/btone/stdio-proxy/index.mjs"]
    }
  }
}
```

Quit Claude Desktop completely (Cmd+Q) and relaunch. Open a new chat. The
plug icon should list `btone-b` with two tools, `eval` and `eval_status`.

Try a prompt like: "Use the eval tool to print the player's coordinates."
Claude should call `eval` with something like
`"val p = mc.player; \"${p?.blockX},${p?.blockY},${p?.blockZ}\""`.

## 6. Failure modes & first debug steps

| Symptom | Likely cause | First check |
| --- | --- | --- |
| `curl /health` hangs or refuses | Mod didn't load, port conflict | Prism log tab for stack traces; `lsof -i :25590` |
| `tools/list` returns no `eval` | Tool not registered (mod loaded wrong) | F3 in-game, look for `btone-b` mod count; check `BtoneB.kt` ran |
| `eval` returns `errorMsg: "Couldn't load script definition"` | JiJ didn't bundle Kotlin scripting jars | Rebuild: `nix develop .. --command ./gradlew clean build`; verify JAR is ~55MB |
| `eval` returns `"baritone is null"` | Baritone standalone not installed in `mods/` | Drop `baritone-standalone-fabric-1.15.0.jar` into the instance's `mods/` |
| 401 on `/mcp` | Stale `$TOKEN` (mod restarted) | Re-run the `jq -r .token ...` extraction; tokens regenerate every launch |
| Claude Desktop shows no tools | stdio-proxy can't find config | `BTONE_CONFIG=/path/to/btone-bridge.json` in the `env` of the MCP entry |
| `path` events never fire on `/events` | Baritone listener registered before player joined a world | Disconnect, rejoin; `GameEvents` re-registers on each `JOIN` |

For deeper debugging, tail `~/Library/Application Support/Claude/logs/mcp-server-btone-b.log`
to see exactly what Claude sent the proxy and what came back.
