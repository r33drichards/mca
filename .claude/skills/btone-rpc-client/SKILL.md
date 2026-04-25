---
name: btone-rpc-client
description: Use when an agent needs to drive btone-mod-c (the Minecraft Fabric bot) from outside MC's JVM. Covers the four supported clients — bash CLI (bin/btone-cli), Python, Go, TypeScript — all auto-generated from one OpenRPC spec. Includes how to discover methods, the bridge config layout, and the recurring gotchas that make naive code break (kill-aura hijacking selected slot, baritone.mine deadlocks, sneak before walking on farmland, water-source preservation).
---

# btone-rpc-client

The mod exposes ~50 JSON-RPC methods over HTTP. Pick a client by what you're writing:

| Use case | Client | Where |
|---|---|---|
| One-shot in a shell loop, easy to pipe to jq | `bin/btone-cli` | bash |
| Quick prototype script, REPL exploration | Python | `clients/python/btone_client.py` |
| Production agent, single-binary distribution | Go | `clients/go/btone/btone.go` (+ `cmd/btone-cli`) |
| Web UI, Node service, Bun script | TypeScript | `clients/typescript/src/btone_client.ts` |

**All four are generated from `proto/btone-openrpc.json`.** The mod ships that exact file in its jar and serves it via `rpc.discover`. If a method or param shape is documented somewhere, the spec is the source of truth — clients catch up by re-running `python3 bin/generate-clients.py`.

## The bridge

`btone-mod-c` writes a config file when MC starts:

```json
// ~/btone-mc-work/config/btone-bridge.json
{ "port": 25591, "token": "<random>", "version": "0.1.0" }
```

All clients read that path by default. POST to `http://127.0.0.1:<port>/rpc` with header `Authorization: Bearer <token>` and body `{"method": "...", "params": {...}}`.

Responses always have shape `{"ok": true, "result": ...}` or `{"ok": false, "error": {"code", "message"}}`.

## Discover methods

Don't guess method names — ask the bot:

```bash
bin/btone-cli list                    # one-line summary per method
bin/btone-cli describe player.teleport  # full param/result schema
bin/btone-cli rpc.discover | jq '.methods | length'  # count
```

Same with the Python REPL:

```python
from btone_client import BtoneClient
bot = BtoneClient()
spec = bot.rpc_discover()
# Find every method that accepts a 'side' param:
[m["name"] for m in spec["methods"] if any(p["name"] == "side" for p in m["params"])]
```

Re-discover every time you start a fresh agent session — methods can be added between mod versions.

## The four clients in one paragraph

```bash
# bash
bin/btone-cli player.state | jq -c '{name, hp:.health, pos:.blockPos}'
bin/btone-cli baritone.goto --params '{"x":1004,"y":69,"z":822}'
bin/btone-cli world.screenshot --params '{"width":640}' | jq -r '.image' | base64 -d > view.png
```

```python
from btone_client import BtoneClient                 # PYTHONPATH=clients/python
bot = BtoneClient()
bot.player_state()
bot.baritone_goto({"x": 1004, "y": 69, "z": 822})
bot.world_place_block({"x": 1004, "y": 67, "z": 820, "side": "up"})
```

```go
import "github.com/r33drichards/mca/clients/go/btone"
c, _ := btone.New()
raw, _ := c.PlayerState()                            // returns json.RawMessage
c.CallTyped("baritone.goto", map[string]any{"x": 1004, "y": 69, "z": 822}, nil)
```

```ts
import { BtoneClient } from "btone_client";
const bot = new BtoneClient();
await bot.playerState();
await bot.baritoneGoto({ x: 1004, y: 69, z: 822 });
```

Method names map mechanically:
- JSON-RPC: `player.set_rotation`
- bash CLI: `btone-cli player.set_rotation`
- Python: `bot.player_set_rotation(...)`
- Go: `c.PlayerSetRotation(...)`
- TypeScript: `bot.playerSetRotation(...)`

When in doubt, the regenerator (`bin/generate-clients.py`) is the only place that decides naming.

## Gotchas the spec doesn't tell you

These bite every fresh agent. The spec describes the methods; this section describes the **bot's behavior under those methods.**

### `baritone.mine` deadlocks the client thread — use `baritone.command`

Verified across multiple MC restarts: `baritone.mine` runs on `ClientThread.call(1_000, ...)` and inside that, `getMineProcess().mine(q, blocks)` blocks indefinitely. Once it hits, every subsequent RPC times out and MC has to be killed.

```bash
# WRONG — deadlocks
btone-cli baritone.mine --params '{"blocks":["minecraft:stone"],"quantity":-1}'

# RIGHT — runs on a dedicated worker thread
btone-cli baritone.command --params '{"text":"mine minecraft:stone"}'
btone-cli baritone.command --params '{"text":"mine minecraft:stone minecraft:coal_ore"}'
btone-cli baritone.command --params '{"text":"stop"}'
```

### kill-aura / auto-weapon hijack the selected hotbar slot

Meteor's `kill-aura` and `auto-weapon` modules quietly switch the selected hotbar slot to whatever weapon they want. When you `container.click {mode: SWAP}` to put a build block into the mainhand, the SWAP succeeds at the inventory level — but the *selected slot* doesn't change, so `world.place_block` (default `hand: "main"`) ends up using the axe.

Two fixes:

```python
# Option A: use the offhand. SWAP to slot 45 (offhand), then place with hand="off".
bot.container_open_inventory()
bot.container_click({"slot": <where the block is>, "button": 40, "mode": "SWAP"})
bot.container_close()
bot.world_place_block({"x": x, "y": y, "z": z, "hand": "off", "side": "up"})

# Option B: disable the modules during the build, then re-enable.
for m in ("kill-aura", "auto-weapon", "auto-tool"):
    bot.meteor_module_disable({"name": m})
# ... do work ...
for m in ("kill-aura", "auto-weapon", "auto-tool"):
    bot.meteor_module_enable({"name": m})
```

In practice the offhand approach is more reliable — it's how the farm-platform routine in this repo places hundreds of blocks per session.

### Walking on farmland reverts it to dirt

After tilling with a hoe, the next time the bot walks across a row it can trample the freshly-tilled cells. Two precautions:

1. `baritone.command "set allowSprint false"` and `set allowParkour false` before any work over farmland.
2. Hold sneak while walking: `player.press_key {"key":"sneak","action":"press"}` before, `"release"` after. Sneaking prevents falls onto farmland from trampling. (Sneak alone won't always stop baritone from breaking allowSprint=false rules; do both.)

### `world.mine_block` only does ONE attack tick

`attackBlock(pos, side)` is the START of breaking. To actually break a stone block, vanilla MC needs `updateBlockBreakingProgress` driven once per tick until the block pops. Use `world.mine_down` (continuous, downward) or `baritone.command "mine <id> [N]"` (any direction) for real mining.

```python
# BROKEN — attempts one tick of breaking, never finishes a stone block.
for y in range(67, 73):
    bot.world_mine_block({"x": 1008, "y": y, "z": 820})

# WORKS — drives updateBlockBreakingProgress from the tick callback.
bot.world_mine_down({"count": 6})

# WORKS — use baritone for a target block id.
bot.baritone_command({"text": "mine minecraft:stone 6"})
```

### Container.close drops the result slot's contents

When you craft something at a crafting table, the result slot is a *phantom* — its contents only become real when you click them out. If you `container.close` while a hoe is still sitting in the result slot, the hoe is **discarded** (not the inputs; those return to inventory).

```python
# Workflow: craft sticks then iron hoe at one table-open.
bot.container_open({"x": 1011, "y": 69, "z": 828})

# Sticks: 2 planks vertically, take output via QUICK_MOVE
bot.container_click({"slot": 44, "button": 0, "mode": "PICKUP"})    # cursor = planks
bot.container_click({"slot": 1, "button": 1, "mode": "PICKUP"})      # 1 plank to grid 1
bot.container_click({"slot": 4, "button": 1, "mode": "PICKUP"})      # 1 plank to grid 4
bot.container_click({"slot": 44, "button": 0, "mode": "PICKUP"})     # deposit cursor
bot.container_click({"slot": 0, "button": 0, "mode": "QUICK_MOVE"})  # take 4 sticks

# Iron hoe: 2 iron + 2 sticks
bot.container_click({"slot": 14, "button": 0, "mode": "PICKUP"})
bot.container_click({"slot": 1, "button": 1, "mode": "PICKUP"})
bot.container_click({"slot": 2, "button": 1, "mode": "PICKUP"})
bot.container_click({"slot": 14, "button": 0, "mode": "PICKUP"})
# ... sticks the same way ...

# IMPORTANT: PICKUP the result before closing. QUICK_MOVE may silently fail on
# the result slot in 1.21.8 — use PICKUP and then deposit somewhere.
bot.container_click({"slot": 0, "button": 0, "mode": "PICKUP"})
bot.container_click({"slot": <empty inv slot>, "button": 0, "mode": "PICKUP"})
bot.container_close()
```

### Don't `world.place_block` near a water source you want to preserve

`world.place_block` succeeds when the target is `air` or `water` — water is replaceable. If you aim at a placement that miss-routes through a water source (because the target cell is unexpectedly non-replaceable and MC offsets to `target+side`), you can silently overwrite the water with dirt/cobble. Verify post-placement with `world.block_at` if water layout matters.

### Spawn protection silently no-ops world mutations near server spawn

If `world.place_block` or `world.mine_block` returns a successful `class_9860`/`Success` ActionResult but the block doesn't change, check if you're inside the server's spawn-protection radius. Either op the bot from your own client, or set `SPAWN_PROTECTION=0` in the server config. The mod has no insight into this; it's a server-side filter.

## Regenerating clients

Whenever the mod's `Schema.java` changes (new method, new param, new type):

```bash
cd mod-c && nix develop .. --command ./gradlew generateOpenRpc   # writes proto/btone-openrpc.json
python3 bin/generate-clients.py                                  # regenerates Go/Python/TS
cd clients/go && nix develop ../.. --command go build -o ../../bin/btone-cli ./cmd/btone-cli
```

The mod jar bundles `proto/btone-openrpc.json` automatically as part of `processResources`, so a fresh `gradle build` keeps `rpc.discover` in sync at runtime. Don't hand-edit `clients/*/btone*` files — the codegen overwrites them.

## When NOT to use a generated client

The auto-generated typed methods cover every method in the spec, but they all take `params` as a free-form map/dict/object. If you're writing a complex routine and want compile-time checking on field names, define your own typed structs/classes that wrap the underlying `call`. The clients also expose `client.call(method, params)` (Python/TS) or `client.Call(method, params)` (Go) so you can call ad-hoc methods that aren't in the spec yet.
