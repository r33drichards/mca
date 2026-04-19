# mca — Minecraft Client RPC bridge for AI agents

A Fabric 1.21.8 Minecraft mod that exposes the player to an external agent
(Claude Code, your own script, anything that can speak HTTP) over a localhost
JSON-RPC API. Drives a real, rendered, logged-in Minecraft client — not a
headless Mineflayer second-account bot.

The agent can: read player state, look at the world, take screenshots
(annotated with on-screen entity/block coords for multimodal LLMs), pathfind
via Baritone, open + loot chests/barrels, send chat / `/`-commands, toggle
Meteor modules.

```
agent ──HTTP──▶ btone-mod-c (in MC's JVM) ──▶ MinecraftClient + Baritone + Meteor
```

## Requirements

- macOS or Linux with **Nix flakes** (or any JDK 21 + Gradle 8.14).
- **Python 3** + pip (for PortableMC, the launcher we use — no Microsoft
  account required for offline-mode servers).
- An offline-mode Minecraft server (or a Microsoft account if your server
  enforces auth).

## Quick start

```bash
git clone https://github.com/r33drichards/mca.git
cd mca

# Build the mod (uses the included nix flake's JDK 21 + Gradle 8.14)
cd mod-c && nix develop .. --command ./gradlew build && cd ..

# Set up a PortableMC instance with the mod + required deps
./bin/setup-portablemc.sh your.server:25565
# (writes ~/btone-mc-work/, downloads fabric-api, fabric-language-kotlin,
# meteor-client, baritone-api-fabric, and copies in btone-mod-c.)
```

The script ends with the exact `portablemc` command to launch. Run it; the
Minecraft window opens, auto-joins the server, and the mod prints:

```
btone-mod-c listening on 127.0.0.1:25591; config at .../config/btone-bridge.json
```

## Driving the bot from Claude Code (or any agent)

The bot's bridge writes its port and bearer token to
`$WORK/config/btone-bridge.json`. Read them, then talk JSON-RPC over HTTP.

A typical agent-loop in shell looks like this — paste these into Claude
Code's Bash tool calls (this is exactly how the README author drove the bot
to clear chests, equip diamond armor, and mine 400+ blackstone):

```bash
CFG="$HOME/btone-mc-work/config/btone-bridge.json"
PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
BASE="http://127.0.0.1:$PORT"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
rpc() { curl -s -X POST "$BASE/rpc" "${H[@]}" -d "$1"; }

# Where am I? what's my state?
rpc '{"method":"player.state"}' | jq -c
# → {"inWorld":true,"pos":{"x":498,"y":69,"z":919},"health":20,"food":20,"name":"Bot",...}

# Walk somewhere
rpc '{"method":"baritone.goto","params":{"x":554,"y":66,"z":906}}'

# What's nearby?
rpc '{"method":"world.blocks_around","params":{"radius":8}}' \
  | jq '.result.blocks | map(select(.id|test("chest|barrel")))'

# Vision — return a base64 PNG that the multimodal LLM can read directly
rpc '{"method":"world.screenshot","params":{"width":640,"yaw":180,"pitch":-5}}' \
  | jq -r '.result.image' | base64 -D > /tmp/view.png
# Then in Claude Code: Read /tmp/view.png
```

For the screenshot pipeline specifically, the response also includes a
side-channel `annotations` object:

```json
{
  "image": "<base64 png>",
  "camera": {"yaw": 180, "pitch": -5, "pos": {"x":498,"y":69,"z":919}},
  "annotations": {
    "entities": [{"entityId":1234,"type":"minecraft:villager","name":"Armorer",
                  "screen":{"x":231,"y":12,"w":9,"h":3},
                  "world":{"x":554.5,"y":69.9,"z":909.5},"distance":16.0}],
    "blocks":   [{"id":"minecraft:chest","screen":{"x":189,"y":25},
                  "world":{"x":444,"y":79,"z":850},"distance":12.2}],
    "lookingAt": {"kind":"block","id":"minecraft:dirt","world":{...}}
  }
}
```

The agent reads the PNG with its multimodal LLM AND uses `annotations` to
chain "I see a chest at pixel (189, 25)" into `container.open(444, 79, 850)`
— no separate OCR or "find the chest" step needed.

## RPC method catalog

Spelled out fully in [`mod-c/SMOKE.md`](mod-c/SMOKE.md). One-line summary:

| Group | Methods |
|---|---|
| **player** | `state`, `inventory`, `equipped`, `respawn` |
| **world (read)** | `block_at`, `blocks_around` (≤r 8), `raycast` |
| **world (write)** | `mine_block`, `place_block`, `use_item`, `interact_entity` |
| **world (vision)** | `screenshot {yaw?, pitch?, width?, includeHud?}`, `screenshot_panorama {angles: 4|6|8}` |
| **chat** | `send {text}` (auto-routes `/`-prefix to `sendChatCommand`), `recent {n}` |
| **container** | `open {x,y,z}`, `state`, `click {slot, button, mode}`, `close` |
| **baritone** | `goto {x?,y?,z?}`, `stop`, `status`, `mine {blocks:[...], quantity}`, `follow {entityName}`, `get_to_block {blockId}`, `thisway {distance}`, `command {text}`, `setting`, `setting_get` |
| **meteor** | `modules.list`, `module.enable {name}`, `module.disable {name}`, `module.toggle {name}`, `module.is_active {name}`, `module.settings_list {name}`, `module.setting_get {name, setting}`, `module.setting_set {name, setting, value}` |
| **events** (SSE) | `GET /events` — chat, joined, disconnected, path |

## Recommended Meteor modules to enable on first launch

```bash
for mod in auto-eat auto-armor auto-tool auto-weapon kill-aura; do
  rpc "{\"method\":\"meteor.module.enable\",\"params\":{\"name\":\"$mod\"}}"
done
```

These keep the bot alive (auto-eats food when hungry, equips best armor it
finds, swings at hostile mobs, picks the right tool for whatever it's
mining). With these on, the agent can issue high-level intents like
"mine 400 blackstone" and the bot survives unattended.

## Watch out

- **Spawn protection** silently no-ops world mutations near server spawn.
  Either `/op Bot` from your own client or set `SPAWN_PROTECTION=0` in
  the server config.
- **Baritone `allowBreak=false`** silently disables `baritone.mine`. If
  mining isn't working, run `baritone.command "set allowBreak true"` to
  re-enable.
- **Use `baritone-api-fabric-X.Y.Z.jar`** in mods/, NOT
  `baritone-standalone-fabric-X.Y.Z.jar`. The standalone variant
  obfuscates the `baritone.api.*` packages and our mod can't link to it
  at runtime. (`bin/setup-portablemc.sh` already pulls the API variant.)
- **In production Fabric, MC classes have intermediary names**
  (`net.minecraft.class_310` not `MinecraftClient`). The mod's handlers
  are written in Java and Loom-remapped, so this is invisible to the
  agent — but if you ever want to extend the mod, write yarn names in
  source and let Loom remap.

There's a longer post-mortem of these gotchas in the
[`minecraft-fabric-mod-bridge`](https://github.com/r33drichards) Claude Code
skill, distilled from this project's smoke-test diary.

## Repository layout

```
mod-c/                    Fabric mod source (Java)
  src/main/java/com/btone/c/
    BtoneC.java             ClientModInitializer
    handlers/               RPC handlers (player, world, baritone, vision, meteor, ...)
    http/, rpc/, events/    HTTP server + JSON-RPC dispatch + SSE
  SMOKE.md                  RPC method runbook with curl examples
flake.nix                 nix devshell — JDK 21 + Gradle 8.14
bin/setup-portablemc.sh   one-shot launcher setup
coords.md                 project-specific notes (which buildings are where on the test world)
docs/plans/               design docs for option C and the vision subsystem
```

## License

MIT.
