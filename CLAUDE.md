# CLAUDE.md — repo guidance for Claude Code sessions

## Always read first

- **[`coords.md`](coords.md)** — concrete coordinates of buildings, hazards,
  and resource caches in the user's test world (`centerbeam.proxy.rlwy.net:40387`).
  Includes the spawn area, the wrong buildings the bot keeps confusing for
  the right one, the chest-wall location with all the diamond gear, the
  bot's stash drop point ("Warehouse 2"), and the **iron/wood stash** in
  Warehouse 1's center row at `(459-460, 72-74, 831)` used to CRAFT iron
  pickaxes (the loot wall is out of diamond picks — fall back to crafting
  iron, recipe and crafting-table coord in `coords.md`). When the user asks
  the bot to go somewhere named, check here first before trying to find it
  via panorama.

- **[`mod-c/SMOKE.md`](mod-c/SMOKE.md)** — the full RPC method runbook with
  curl examples for everything the bot can do.

- **[`README.md`](README.md)** — clone → build → launch → drive flow,
  including the standard `rpc()` shell helper.

## How the agent drives the bot

The bot's bridge is HTTP at `127.0.0.1:<port>` with a Bearer token, both
in `~/btone-mc-work/config/btone-bridge.json`. Standard preamble for any
agent loop:

```bash
CFG="$HOME/btone-mc-work/config/btone-bridge.json"
PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
BASE="http://127.0.0.1:$PORT"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
rpc() { curl -s -X POST "$BASE/rpc" "${H[@]}" -d "$1"; }
```

## Behavioral rules

- **Update `coords.md` whenever the user names a new location.** That's the
  long-lived memory across sessions. Bot positions, chest stashes, and
  hazards live there.
- **Don't reflexively patch handlers when something silently fails.** Check
  spawn protection first (`coords.md` documents the radius), check whether
  Baritone has `allowBreak=false`, check whether the bot is too far from
  the target (container.open requires adjacency).
- **Vision before walking.** If asked to find a building, take a 4-direction
  panorama and look at the images yourself before guessing coordinates.
- **Don't take everything from a chest.** When looting, take ONE of each
  needed type then close. The auto-armor module will equip it. "Take all"
  spam tends to lose items to inventory full / shift-click race conditions.
- **Save coords for any non-obvious location** the bot reaches. Future
  agent sessions need that map.
- **Never ask the user to physically free the bot.** If the bot is stuck
  (basalt pocket, bedrock layer, lava trap), it's your problem to solve via
  the RPC surface — `baritone.mine` to break adjacent blocks, `world.mine_block`
  in a continuous loop, `world.place_block` (with the `side` param) to build
  a staircase out, `player.pillar_up` once basalt is in the hotbar, swap items
  via `container.open_inventory` + SWAP, or in the worst case force a death
  by walking into lava (with planks for Your-Items-Are-Safe insurance) and
  recover from overworld spawn. Asking the user to break a block is a
  failure mode of the routine, not a design choice — extend mod-c and the
  skill until the bot can self-rescue. Document each new self-rescue trick
  in the skill's Common Failure Modes table.

## Mod-c specifics

- Mod is built from `mod-c/` with `nix develop .. --command ./gradlew build`
  inside that directory.
- After a code change, redeploy: `cp mod-c/build/libs/btone-mod-c-0.1.0.jar
  ~/btone-mc-work/mods/`, kill MC, relaunch via `portablemc`.
- Bot dies a lot. Default reaction to `inWorld:true, health:0.0` is
  `rpc '{"method":"player.respawn"}'`, then walk back to the death
  coordinates (visible in `chat.recent`) to grab dropped items before
  they despawn (~5min window).
