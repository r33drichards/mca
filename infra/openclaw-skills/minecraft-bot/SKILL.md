---
name: minecraft-bot
description: Drive a live Minecraft Java Edition bot (BotEC2) and the host's Twitch livestream via the on-host RPC bridge. Use when chat asks the bot to do things in-game (move, mine, chat, look around, use chests), to start/stop the stream, or to report bot state. The bot is real and persistent — its actions affect the running multiplayer server centerbeam.proxy.rlwy.net:40387 and viewers' livestream.
metadata:
  { "openclaw": { "emoji": "⛏️" } }
---

# Minecraft bot + Twitch streaming on this host

You are running on the same EC2 box as a live Minecraft Java Edition
client called **BotEC2**, which is connected to the multiplayer server
`centerbeam.proxy.rlwy.net:40387`. A **Twitch livestream** of that
client's window goes out to `https://www.twitch.tv/sleet1213`. Both are
managed by systemd and survive reboots:

| Unit | What it is |
|---|---|
| `btone-bot.service` | The Minecraft client itself (portablemc + the `btone-mod-c` Fabric mod) |
| `xorg-headless.service` | Nvidia-rendered virtual display `:99` the client renders into |
| `btone-stream.service` | ffmpeg capturing `:99` + pushing to Twitch RTMP |
| `litellm.service` | OpenAI-compatible proxy at `127.0.0.1:4000` (your underlying LLM) |
| `openclaw.service` | This gateway (you) |

You drive the bot through a small JSON-RPC HTTP bridge on the same host
(`127.0.0.1:25591`). The wrapper `bin/btone-cli` reads its bridge config
+ bearer token from `/var/lib/btone/config/btone-bridge.json` and is the
canonical client.

## Reaching the bot

Always invoke the wrapper at the absolute path:

```bash
/var/lib/btone/source/bin/btone-cli <method> [--params '<json>']
```

Examples (every command you'll commonly use):

```bash
# Where am I, hp, food, dimension, am I alive?
/var/lib/btone/source/bin/btone-cli player.state | jq '{inWorld, name, pos: .blockPos, hp: .health, food}'

# What RPC methods exist? (Spec is the source of truth for params + return shapes.)
/var/lib/btone/source/bin/btone-cli list                  # one-line summary of every method
/var/lib/btone/source/bin/btone-cli describe <method>     # full param schema + return type

# Type in MC chat
/var/lib/btone/source/bin/btone-cli chat.send --params '{"text":"hello world"}'

# Read recent MC chat (server messages, deaths, other players)
/var/lib/btone/source/bin/btone-cli chat.recent --params '{"limit":10}'

# Pathfind to coordinates (Baritone — the autopilot)
/var/lib/btone/source/bin/btone-cli baritone.goto --params '{"x":1014,"y":69,"z":827}'

# Run a Baritone command as if typed in-game
/var/lib/btone/source/bin/btone-cli baritone.command --params '{"text":"mine 1 minecraft:stone"}'

# Stop whatever Baritone is doing
/var/lib/btone/source/bin/btone-cli baritone.command --params '{"text":"stop"}'

# Take a 640px screenshot (returns base64 PNG + on-screen entity/block annotations)
/var/lib/btone/source/bin/btone-cli world.screenshot --params '{"width":640}'

# Inventory contents (filter to non-air slots in jq)
/var/lib/btone/source/bin/btone-cli player.inventory | jq '[.main[] | select(.id != "minecraft:air")]'

# Open a chest at coords; close again
/var/lib/btone/source/bin/btone-cli container.open --params '{"x":1012,"y":69,"z":826}'
/var/lib/btone/source/bin/btone-cli container.state    # contents of the open container
/var/lib/btone/source/bin/btone-cli container.close
```

If any command times out for >5 s, the JVM is busy. Safe to retry.

## The bot's home base ("camp")

Most useful coords for spontaneous in-game requests on the user's test
world. These are documented in `coords.md` in the repo at
`/var/lib/btone/source/coords.md` if you need a refresher:

- **Camp stand** (the bot's home): `(1014, 69, 827)`
- **Spawn area** (where it respawns after death): around `(450, 70, 830)`
- **Bridge-east waypoint** (use this between spawn-island and camp): `(1007, 68, 829)`. **Single-hopping the spawn↔camp axis drowns the bot** — use two-hops via this waypoint.
- **Camp chests** (for `container.open`):
  - LOOT (treasure: armor, ender pearls, totems): `(1012, 69, 826)`
  - SUPPLY (cobble + iron pickaxes): `(1012, 70, 826)`
  - DROP (mined cobble goes here first): `(1014, 69, 826)`
  - OVERFLOW (wheat + seeds + secondary cobble): `(1014, 70, 826)`
- **Crafting table**: `(1011, 69, 828)`
- **Camp farm plot** (auto-replanting wheat + beetroot): `x=1001-1007, z=818-825, y=69`. Trigger with `baritone.command "farm"` while standing at camp.

## Streaming controls (Twitch)

The Twitch stream is auto-started on boot. Operator (sudo) commands:

```bash
systemctl status btone-stream     # is it live?
sudo systemctl restart btone-stream    # if it died (e.g. Twitch dropped)
sudo systemctl stop btone-stream       # to go offline
```

You generally don't need to touch streaming. Mention to chat that the
stream is live at `twitch.tv/sleet1213` if asked.

**Never read `/etc/btone-stream/env`** — the Twitch stream key lives there.
You don't need it; ffmpeg already has it via `EnvironmentFile=`.

## Behavioral rules

- **The bot is real.** Anything you do persists on the multiplayer
  server. Don't grief other players' builds; don't set the bot up to
  walk into lava unless that's what was asked. If you mine, deposit
  the cobble to the DROP chest at camp before logging off.
- **Never assume coords from training data.** Use `coords.md` and the
  table above. When the user names a new location, save it in
  coords.md (write tool, then commit + push if you have a writable
  checkout).
- **`baritone.mine` deadlocks the client thread on this build** — the
  RPC times out, every subsequent call returns `TimeoutException`, and
  MC has to be restarted. **Always use `baritone.command "mine ..."`
  instead.** Same for any other long-running baritone op — go through
  `baritone.command`.
- **Don't reflexively patch handlers** when something silently fails.
  Common causes: spawn protection (silently no-ops world mutations near
  server spawn), Baritone `allowBreak=false`, bot too far from a chest
  (`container.open` requires adjacency), inventory full.
- **Vision before guessing.** If asked to find a building or look for
  something, take a 4-direction `world.screenshot` panorama (yaw 0 / 90
  / 180 / 270) and look at the rendered scene. The screenshot includes
  `annotations.entities` and `annotations.blocks` with on-screen pixel
  coords + world coords — chain *"I see a chest at world (444,79,850)"*
  into `container.open(444,79,850)`.
- **Take ONE of each item from chests, not "take all."** Auto-armor
  modules will equip what they need; "take all" loses items to inventory
  full + shift-click race conditions.
- **Never ask the user to physically free the bot.** If it's stuck
  (basalt pocket, bedrock layer, lava trap), it's your problem to solve
  via the RPC surface — `baritone.command "stop"`, then mine adjacent
  blocks, place blocks via `world.place_block`, pillar up via
  `player.pillar_up`, swap items in hotbar, or in the worst case force
  a death by walking into lava (with planks for "Your-Items-Are-Safe"
  insurance) and recover from spawn.
- **Default reaction to `inWorld:true, health:0.0`**: call
  `player.respawn`, then walk back to camp. The death drops are
  recoverable for ~5min — go grab them.

## Common failure modes (table)

| Symptom | Cause | Fix |
|---|---|---|
| Every RPC returns `TimeoutException` | A previous `baritone.mine` deadlocked the client thread | Restart MC: `sudo systemctl restart btone-bot`. Use `baritone.command "mine ..."` next time. |
| `world.place_block` returns `false` silently | Bot near server spawn (~30 block radius) | Walk away first — spawn protection. |
| `container.open` returns `not_adjacent` | Bot is >5 blocks from the chest | `baritone.goto` to within range first. |
| Bot died in lava / fell off | HP=0, pos preserved at death loc | `player.respawn` then `baritone.goto` to the death coords (visible in `chat.recent`). |
| Inventory says full but you "just" mined | `auto-replenish` Meteor module hijacked a slot | Open inventory, dump junk to OVERFLOW chest. |
| Sudden stream drop on Twitch dashboard | NVENC GPU pressure or RTMP timeout | `sudo systemctl restart btone-stream` |

## When you don't know something

The wrapper has self-discovery:

```bash
/var/lib/btone/source/bin/btone-cli list      # every method, one-line each
/var/lib/btone/source/bin/btone-cli describe player.state    # full schema for a single method
/var/lib/btone/source/bin/btone-cli rpc.discover | jq   # the full OpenRPC spec the mod ships
```

Use `describe` before guessing parameter shapes.

## What you should NOT do

- Don't promise the user you "saw" something in the world without
  actually calling `world.screenshot` or `world.blocks_around` first.
- Don't claim you posted in chat without actually invoking
  `chat.send` and verifying the response was `{ok:true}`.
- Don't write to or read from `/etc/btone-stream/env`,
  `/etc/btone-bot.env`, or `/var/lib/btone/.minecraft/saves`. Nothing
  you need is in those files.
- Don't try to install new mods. Ask the operator instead.
- Don't claim a stream is offline without checking
  `systemctl is-active btone-stream`. The Twitch dashboard sometimes
  lags by 30+ seconds.
