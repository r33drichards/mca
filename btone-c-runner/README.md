# btone-c-runner

A small launcher for [`mcp-v8`](https://github.com/r33drichards/mcp-js)
configured for the `btone-mod-c` data-pipe.

The mod itself is just an HTTP server living inside Minecraft. The agent
loop happens in JavaScript, executed by mcp-v8 as an MCP `run_js` tool.
Whatever MCP host you use (Claude Desktop, your own client, etc.) talks
to mcp-v8 over MCP and gets back the script's return value.

## One-time setup

```bash
# 1. Install mcp-v8.
curl -fsSL https://raw.githubusercontent.com/r33drichards/mcp-js/main/install.sh | sudo bash
which mcp-v8

# 2. Wire it into your MCP host (Claude Desktop, Continue, etc.). Edit the
#    host's MCP config to include:
#
#    {
#      "mcpServers": {
#        "btone-c-v8": {
#          "command": "/absolute/path/to/btone/btone-c-runner/start-mcp-v8.sh",
#          "args": [],
#          "env": {}
#        }
#      }
#    }
#
#    See claude-desktop-config.example.json in this directory.
```

## What the agent needs to know

When you launch a fresh Minecraft session, `btone-mod-c` writes
`<.minecraft>/config/btone-bridge.json` with the chosen port (`25591` by
default) and a fresh per-session bearer token. The agent must call the
HTTP API with that token.

For ad-hoc smoke testing, copy the token into your prompt:

```
You have an mcp-v8 tool. The btone-mod-c HTTP API is at
http://127.0.0.1:25591/rpc with bearer token <PASTE TOKEN>.

POST /rpc {"method": "...", "params": {...}}
Wire format: {"ok": true, "result": ...} or {"ok": false, "error": {...}}.

Method list: call debug.methods to see them all.
```

`mcp-v8` runs `fetch()` natively, so the script body is straightforward.
See `examples/quick-walk.js` for a representative loop (move via baritone,
poll status, return final position).

## Method catalog

`debug.echo` and `debug.methods` for sanity. Real handlers:

| Method | Params | Returns |
|---|---|---|
| `player.state` | — | `{inWorld, pos, blockPos, rot, health, food, dim, name}` |
| `player.inventory` | — | `{inWorld, hotbarSlot, main:[{slot,id,count}]}` |
| `player.equipped` | — | `{mainHand, offHand}` |
| `world.block_at` | `{x,y,z}` | `{id, air}` |
| `world.blocks_around` | `{radius?}` | `{blocks:[{x,y,z,id}]}` |
| `world.raycast` | `{max?}` | `{type, [x,y,z,side,id], [entityId,entityType,entityName]}` |
| `chat.send` | `{text}` | `{sent}` (auto-detects `/commands`) |
| `chat.recent` | `{n?}` | `{messages:[]}` |
| `world.mine_block` | `{x,y,z}` | `{started, side}` |
| `world.place_block` | `{x,y,z,hand?}` | `{result}` |
| `world.use_item` | `{hand?}` | `{result}` |
| `world.interact_entity` | `{entityId, hand?}` | `{result}` |
| `container.open` | `{x,y,z}` | `{requested}` (poll `container.state`) |
| `container.state` | — | `{open, screen, syncId, slots[]}` |
| `container.click` | `{slot, button?, mode?}` | `{clicked}` |
| `container.close` | — | `{closed}` |
| `baritone.goto` | `{x,z}`, `{x,y,z}`, or `{y}` | `{started, goal}` |
| `baritone.stop` | — | `{stopped}` |
| `baritone.status` | — | `{hasBaritone, active, [goal]}` |
| `baritone.mine` | `{blocks:[id], quantity?}` | `{started, count}` |
| `baritone.follow` | `{entityName}` | `{started}` |
| `baritone.setting` | `{key, value}` | `{applied, value}` |
| `baritone.setting_get` | `{key}` | `{key, value}` |
| `meteor.modules.list` | — | `{modules:[]}` (only if Meteor installed) |
| `meteor.module.{enable,disable,toggle}` | `{name}` | `{ok}` |
| `meteor.module.is_active` | `{name}` | `{name, active}` |

`GET /events` is an SSE stream (`chat`, `joined`, `disconnected`, `path`).
The agent can `fetch(...).body.getReader()` it from JS if mcp-v8's runtime
exposes streaming -- otherwise just poll `chat.recent`.

## Local test (without Minecraft)

```bash
# Build the mod (in another shell):
cd mod-c && nix develop .. --command ./gradlew build

# Curl the API end-to-end (Minecraft must be running):
TOKEN=$(jq -r .token "$HOME/Library/Application Support/PrismLauncher/instances/btone-c-dev/.minecraft/config/btone-bridge.json")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25591/health
curl -s -X POST http://127.0.0.1:25591/rpc \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"method":"debug.methods"}' | jq
```
