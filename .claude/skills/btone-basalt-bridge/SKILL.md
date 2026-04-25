---
name: btone-basalt-bridge
description: Use when an agent driving a btone-mod-c Minecraft Bot is asked to bridge over water or a gap with basalt (or any block). The safe recipe — armor + basalt + `world.place_block` + `baritone.goto` step loop — that survives a walk through the spawn-island geometry without drowning and places at 4-5 blocks per second.
---

# Basalt bridge routine for the btone Bot

## The loop

```
1. PREFLIGHT     (alive + armored + basalt in hotbar + on solid ground at safe y)
2. WALK          (get to the bridge start tile with two-hop baritone waypoints)
3. BRIDGE        (per-tile: face direction → place_block → baritone.goto +1 → loop)
4. REPORT        (one push-notification on completion or depletion; stop)
```

The steady state is iterations of steps 1-3. A single PREFLIGHT failure means
"regear first" — see the btone-nether-mining-routine skill for the regear
scripts (same chests, same SWAP patterns).

## Why a place+step shell loop, not `player.bridge` or `world.bridge`

Three approaches were tried on this server (centerbeam.proxy.rlwy.net:40387,
verified 2026-04-23). Only the third works reliably.

### ❌ `player.bridge` (BRIDGE_FLAT mode in MovementTasks.java)

Holds `forward + use + sneak` keys and relies on vanilla's useKey raycast
to pick a place target. Fails at platform edges: the bot's eye is 1.62m
above the floor block top, pitch is 70°, and the raycast reaches the
east face plane at `y = floor_top + 0.25`, which is ABOVE the block's
top face. No face hit → no block placed → forward pushes the bot off
the edge into water → drowning death within 15 seconds.

### ❌ `world.bridge` tick-task with physics-driven stepping (BridgeTask.java)

Attempt #1 synthesized a `BlockHitResult` on the east face directly
(fixing the raycast-miss), then held `forward + sneak` to let vanilla
physics walk the bot forward. Symptom: `world.bridge` returns
`blocksPlaced:0, ticks:2000, reason:"timeout"` even after a 100s run.

Root cause: `mc.options.forwardKey.setPressed(true)` does NOT actually
move the player on this setup. (The jumpKey + useKey combination that
PillarUpTask uses DOES work, but forward/back/strafe keys don't drive
movement when toggled via setPressed.) Verified 2026-04-23 by holding
the forward key for 5 seconds with repeated press calls — bot position
unchanged to the sixth decimal.

Attempt #2 teleport-stepped via `player.refreshPositionAndAngles` after
each placement. The server's movement validator snaps the bot back on
the next packet; the task ends up oscillating between "place-teleport-
snap-back" with zero net progress.

### ✅ Shell loop: `world.place_block` + `baritone.goto` one tile at a time

Baritone IS able to walk single-tile distances reliably (~0.5-1s per
tile). `world.place_block` with an explicit `side` param synthesizes the
hit result server-side, bypassing the raycast. Driving both from a
shell loop, checking the target block before and after each placement,
gives a proven **~4 blocks/sec** bridge rate with zero bot deaths.

The working loop is in `/tmp/bridge_loop.sh` (see "Step 3: BRIDGE" below
for the full script).

## One-time setup

Standard preamble — every agent shell needs this:

```bash
CFG="$HOME/btone-mc-work/config/btone-bridge.json"
PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
BASE="http://127.0.0.1:$PORT"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
rpc() { curl -s -X POST "$BASE/rpc" "${H[@]}" -d "$1"; }
```

Enable Meteor survival modules (same as nether-mining skill):

```bash
for m in auto-eat auto-armor auto-tool auto-replenish auto-totem \
         auto-respawn kill-aura anti-hunger; do
  rpc "{\"method\":\"meteor.module.enable\",\"params\":{\"name\":\"$m\"}}"
done
```

`auto-armor` is load-bearing: it equips helmets/chestplates/leggings/boots
that land in main inventory into slots 36-39 within ~1s. This is why
"just quick-move armor from chest → main inv" works without manual SWAP.

## Step 1: PREFLIGHT

Five checks before starting the bridge loop:

```bash
STATE=$(rpc '{"method":"player.state"}')
HP=$(echo "$STATE" | jq -r '.result.health')
IN_WORLD=$(echo "$STATE" | jq -r '.result.inWorld')
BY=$(echo "$STATE" | jq -r '.result.blockPos.y')
FOOD=$(echo "$STATE" | jq -r '.result.food')
INV=$(rpc '{"method":"player.inventory"}')
HAS_HELM=$(echo "$INV" | jq -r '.result.main | map(select(.slot==39 and (.id|test("helmet")))) | length')
HAS_CHEST=$(echo "$INV" | jq -r '.result.main | map(select(.slot==38 and (.id|test("chestplate")))) | length')
HAS_BASALT=$(echo "$INV" | jq -r '[.result.main[] | select(.id=="minecraft:basalt") | .count] | add // 0')

# 1a. Alive?
[ "$HP" = "0.0" ] && { echo "DEAD → respawn"; rpc '{"method":"player.respawn"}'; exit 1; }

# 1b. Above water level? If the bot is at y<65, a baritone path dropped it
#     into the ocean; bail and let the regear cycle rehome it.
[ "$BY" -lt 65 ] && { echo "IN WATER y=$BY → rescue first"; exit 2; }

# 1c. Armor equipped?
if [ "$HAS_HELM" = "0" ] || [ "$HAS_CHEST" = "0" ]; then
  echo "missing armor → regear at loot wall (568, 66, 910)"
  exit 3
fi

# 1d. Basalt in any slot?
[ "$HAS_BASALT" -lt 64 ] && { echo "NO BASALT → take from W2 (474, 71, 830)"; exit 4; }

# 1e. Food topped up? auto-eat will consume golden_carrots when hunger drops.
[ "$FOOD" -lt 16 ] && { echo "LOW FOOD — grab golden_carrots from lmoik chest"; exit 5; }
```

Regear procedures are the same as the nether-mining skill's Step 3.
Reuse them.

## Step 2: WALK to a safe start

**The spawn-island east edge is NOT at x=600 like an older notes claim.
The real edge at z=829 is at x=588** (cut_sandstone platform, top y=68).
Past x=589 is the water, broken only by a decorative glowstone "lamp"
pillar at (591, 62, 829).

Verified reference points for the east edge:

| z    | east-edge x | top block       | top y | safe bridge start? |
|------|-------------|------------------|-------|--------------------|
| 823  | 588         | cut_sandstone   | 68    | yes (the anchor)   |
| 829  | 588         | cut_sandstone   | 68    | yes (verified 2026-04-23) |
| 830  | 591-595     | mixed           | 65-69 | no — mixed heights |
| 840  | 478         | oak_planks      | 70    | no — too far west  |

Use `(588, 68, 829)` or `(588, 68, 823)` as the bridge anchor. Walk there
with two-hop baritone waypoints; a single long baritone.goto across the
island routinely descends into water at x~595:

```bash
# Hop 1: over the glass-dome greenhouse (guaranteed land at y>=69)
rpc '{"method":"baritone.goto","params":{"x":498,"y":69,"z":919}}'
until [ "$(rpc '{"method":"baritone.status"}' | jq -r '.result.active')" = "false" ]; do sleep 1; done

# Hop 2: cut_sandstone platform at the east edge
rpc '{"method":"baritone.goto","params":{"x":588,"y":68,"z":829}}'
until [ "$(rpc '{"method":"baritone.status"}' | jq -r '.result.active')" = "false" ]; do sleep 1; done
```

Sanity check: `player.state.blockPos.y >= 65` AND `HP > 0`. If no, the
pathfinder dropped you into water — die+respawn is faster than swimming
back; just `chat.send "/kill"` (or wait for drowning) and start over from
spawn.

## Step 3: BRIDGE

The loop. Uses `world.place_block` (atomic) + `baritone.goto` (reliable
one-tile movement). Paste this as a file and run.

```bash
#!/bin/bash
# /tmp/bridge_loop.sh — basalt bridge via place+step.
# Args: direction=+x|-x|+z|-z (default +x), max_blocks=300 (default)

DIR="${1:-+x}"
MAX="${2:-300}"

CFG="$HOME/btone-mc-work/config/btone-bridge.json"
PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
BASE="http://127.0.0.1:$PORT"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
rpc() { curl -s -X POST "$BASE/rpc" "${H[@]}" -d "$1"; }
say() { echo "[$(date +%H:%M:%S)] $*"; }

# Direction → offset + face name + yaw angle
case "$DIR" in
  "+x") DX=1; DZ=0; FACE="east"; YAW=-90 ;;
  "-x") DX=-1; DZ=0; FACE="west"; YAW=90 ;;
  "+z") DX=0; DZ=1; FACE="south"; YAW=0 ;;
  "-z") DX=0; DZ=-1; FACE="north"; YAW=180 ;;
  *) echo "bad direction $DIR"; exit 1 ;;
esac

# Ensure basalt is the mainHand-held item, swapping from main inv if needed.
# Returns 0 on success, 1 if no basalt anywhere in inventory.
ensure_basalt_hotbar() {
  INV=$(rpc '{"method":"player.inventory"}')
  HELD_SLOT=$(echo "$INV" | jq -r '.result.hotbarSlot')
  HELD_ID=$(echo "$INV" | jq -r --argjson s "$HELD_SLOT" '.result.main | map(select(.slot==$s)) | .[0].id // "empty"')
  [ "$HELD_ID" = "minecraft:basalt" ] && return 0

  # Another hotbar slot has basalt? Swap it into the held slot.
  HBS=$(echo "$INV" | jq -r '.result.main | map(select(.slot<9 and .id=="minecraft:basalt")) | .[0].slot // -1')
  if [ "$HBS" != "-1" ]; then
    say "basalt in hotbar slot $HBS but held is $HELD_ID (slot $HELD_SLOT) — swap needed"
    rpc '{"method":"container.open_inventory"}' >/dev/null; sleep 0.2
    # PICKUP source → PICKUP dest effectively swaps (via cursor).
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$((36+HBS)),\"button\":0,\"mode\":\"PICKUP\"}}" >/dev/null
    sleep 0.1
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$((36+HELD_SLOT)),\"button\":0,\"mode\":\"PICKUP\"}}" >/dev/null
    sleep 0.1
    rpc '{"method":"container.close"}' >/dev/null
    sleep 0.2
    return 0
  fi

  # Main inv has basalt? SWAP it into the held hotbar slot.
  MIS=$(echo "$INV" | jq -r '.result.main | map(select(.slot>=9 and .slot<=35 and .id=="minecraft:basalt")) | .[0].slot // -1')
  if [ "$MIS" != "-1" ]; then
    say "refill: SWAP main-inv slot $MIS into hotbar slot $HELD_SLOT"
    rpc '{"method":"container.open_inventory"}' >/dev/null; sleep 0.2
    # SWAP with button=destination_hotbar, slot=source_inventory.
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$MIS,\"button\":$HELD_SLOT,\"mode\":\"SWAP\"}}" >/dev/null
    sleep 0.2
    rpc '{"method":"container.close"}' >/dev/null
    sleep 0.2
    return 0
  fi

  return 1
}

PLACED=0
STUCK=0
while [ $PLACED -lt $MAX ]; do
  STATE=$(rpc '{"method":"player.state"}')
  HP=$(echo "$STATE" | jq -r '.result.health')
  BX=$(echo "$STATE" | jq -r '.result.blockPos.x')
  BY=$(echo "$STATE" | jq -r '.result.blockPos.y')
  BZ=$(echo "$STATE" | jq -r '.result.blockPos.z')
  [ "$HP" = "0.0" ] && { say "DEAD at placed=$PLACED"; break; }
  [ "$(echo "$HP < 8"|bc -l)" = "1" ] && { say "HP low ($HP)"; break; }
  ensure_basalt_hotbar || { say "OUT OF BASALT at placed=$PLACED"; break; }

  TGT_X=$((BX+DX)); TGT_Y=$((BY-1)); TGT_Z=$((BZ+DZ))
  TGT_ID=$(rpc "{\"method\":\"world.block_at\",\"params\":{\"x\":$TGT_X,\"y\":$TGT_Y,\"z\":$TGT_Z}}" | jq -r '.result.id')

  if [ "$TGT_ID" != "minecraft:air" ] && [ "$TGT_ID" != "minecraft:water" ]; then
    if [ "$TGT_ID" != "minecraft:basalt" ] && [ "$PLACED" -gt 0 ]; then
      say "REACHED LAND: target ($TGT_X,$TGT_Y,$TGT_Z)=$TGT_ID at placed=$PLACED"
      break
    fi
    # Already basalt / pre-bridge natural land → step onto it without placing.
  else
    # Place basalt on the specified face of the current floor block.
    rpc '{"method":"player.set_rotation","params":{"yaw":'"$YAW"',"pitch":30}}' >/dev/null
    sleep 0.1
    rpc "{\"method\":\"world.place_block\",\"params\":{\"x\":$BX,\"y\":$((BY-1)),\"z\":$BZ,\"side\":\"$FACE\"}}" >/dev/null
    sleep 0.25
    TGT_AFTER=$(rpc "{\"method\":\"world.block_at\",\"params\":{\"x\":$TGT_X,\"y\":$TGT_Y,\"z\":$TGT_Z}}" | jq -r '.result.id')
    if [ "$TGT_AFTER" = "minecraft:basalt" ]; then
      PLACED=$((PLACED+1))
      [ $((PLACED%10)) -eq 0 ] && say "placed=$PLACED at ($BX,$BY,$BZ)"
    else
      say "place FAILED at ($TGT_X,$TGT_Y,$TGT_Z) — still $TGT_AFTER"
    fi
  fi

  # Step 1 tile in direction via baritone.
  rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$TGT_X,\"y\":$BY,\"z\":$TGT_Z}}" >/dev/null
  for j in $(seq 1 6); do
    sleep 0.5
    [ "$(rpc '{"method":"baritone.status"}' | jq -r '.result.active')" = "false" ] && break
  done

  # Stuck detection: bot didn't advance. Bail after 3 consecutive same-pos.
  NEW_BX=$(rpc '{"method":"player.state"}' | jq -r '.result.blockPos.x')
  if [ "$NEW_BX" = "$BX" ]; then
    STUCK=$((STUCK+1))
    [ $STUCK -ge 3 ] && { say "STUCK at ($BX,$BY,$BZ) — bailing"; break; }
  else
    STUCK=0
  fi
done

FINAL=$(rpc '{"method":"player.state"}' | jq -c '.result.blockPos')
BAS=$(rpc '{"method":"player.inventory"}' | jq -r '.result.main | map(select(.id=="minecraft:basalt") | .count) | add // 0')
say "DONE placed=$PLACED final=$FINAL basalt_left=$BAS"
```

### Typical timing (verified 2026-04-23)

- Depletion of a 6-stack (384 basalt) hotbar: ~255 blocks bridged + 129
  blocks wasted or in main inv → ~4 minutes of wall-clock run.
- Per-block pace: ~1.1 seconds (baritone step ~0.7s + place_block ~0.3s).
- Re-fires from main inv via `ensure_basalt_hotbar`: ~2s overhead per swap.

### Block choice tradeoff

Basalt is the default (plentiful in Warehouse 2 center-row chest). The
loop accepts any block ID via the `world.place_block` call, but
`ensure_basalt_hotbar` is hardcoded to basalt — change that function if
you bridge with a different block.

| Block               | W2 chest supply          | Float? | Mob-spawn-proof? |
|---------------------|--------------------------|--------|------------------|
| `basalt`            | ~1700+ (primary)         | no     | yes              |
| `blackstone`        | ~few stacks              | no     | yes              |
| `cobbled_deepslate` | rarely in bot inv        | no     | yes              |

Don't bridge with slabs or stairs — their hitbox isn't a full cube and
the baritone-goto step occasionally snags on them.

## Step 4: REPORT

Once the loop stops, notify once and exit:

```bash
if [ "$REASON" = "REACHED LAND" ]; then
  MSG="bridge reached land at +$TOTAL_PLACED blocks east"
else
  MSG="basalt depleted after +$TOTAL_PLACED blocks; bridge at x=$(rpc '{"method":"player.state"}' | jq -r '.result.blockPos.x')"
fi
# PushNotification is the preferred signal if the user may be away.
# rpc '{"method":"chat.send","params":{"text":"'"$MSG"'"}}' also works but is in-game.
echo "$MSG"
```

## Death recovery

If the bot dies mid-bridge:

```bash
rpc '{"method":"player.respawn"}'
sleep 3
# Items dropped at death coords; 5-minute despawn window.
# If death was <20 blocks from the end of the bridge, walk back for items.
# Otherwise skip straight to RESUPPLY — armor+tools at the loot wall and
# basalt at W2 are cheaper to re-grab than the round trip.
```

The server respawns to (470, 76, 831) on top of Warehouse 1.

## Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `world.bridge` (the RPC) returns `blocksPlaced:0, reason:"timeout"` after full `max_ticks` | `mc.options.forwardKey.setPressed(true)` doesn't drive movement on this setup — the tick task's physics loop never walks the bot forward | Use the shell `/tmp/bridge_loop.sh` instead; it uses baritone for the step and works |
| `player.teleport` reported successful but next `player.state` shows same position | Server movement validator snaps the bot back | This server is strict — avoid `player.teleport`; use `baritone.goto` for all position changes |
| `baritone.goto y=70` to east side of spawn island drops bot at y=62 in water | The spawn island geometry has a dip around x=595, z=830 where the top layer is absent; baritone descends through it and then can't climb back | Use two-hop waypoints over (498, 69, 919) then (588, 68, 829); don't trust a single cross-island goto |
| `world.place_block` returns `result:class_9860` (Fail) without placing | Bot is too far from target (>~4.5 blocks) OR the click is on a face the server can't see (obscured by other blocks) | Make sure the bot is ADJACENT to the floor block being clicked; if a prior placement obscures the target, baritone.goto to the next tile first and retry |
| Place-step loop progresses 1 block then stops, bot "stuck" | Baritone can't path to the 1-tile target because the placed block is 1 block below bot's feet and baritone wants to descend but walking-face is blocked | The loop calls baritone.goto(TGT_X, BY, TGT_Z) with BY = CURRENT bot y, not TGT_Y+1; this is correct — make sure the TGT_Y computation is `BY-1` so bot stays at same y stepping over the placed block |
| Bot walks east but held item is iron_pickaxe (or empty) not basalt, no placements | Autotool or auto-replenish is swapping hotbar slot mid-loop; OR the bot picked up items and they landed in the wrong slot | `ensure_basalt_hotbar` in the loop handles this — it re-swaps every iteration. If still failing, check Meteor auto-tool isn't actively re-selecting a pickaxe slot |
| Bot drowning mid-bridge (HP ticking down over water) | Bot fell off a placement edge and is now swimming — check `world.place_block` failures in the loop log | Wrap the loop with a per-iteration HP check; bail at HP<8 and trigger Death Recovery rather than dying mid-bridge and losing gear |

## Extending: bridge direction and start tile

| From | Direction | Bridge reaches (verified 2026-04-23) | Notes |
|------|-----------|--------------------------------------|-------|
| (588, 68, 829) | `+x` | **land at x=1008** (copper_ore terrain) | 419-block bridge across open ocean; two resupply trips. |
| (588, 68, 823) | `+x` | — | Alternate anchor; same ocean east. Not tested to completion. |

The east edge of the spawn island is an arbitrary choice — feel free to
anchor on any platform tile and bridge in any cardinal direction. Change
the `direction` arg of the loop (`+x/-x/+z/-z`) to match.

### Resupply loop pattern (for multi-load bridges)

One inventory-full of basalt (up to ~1500 blocks when grabbing all stacks
from an untapped chest) bridges ~1500 tiles before depletion. For
bridges of known length, one load is usually enough. For open-ended
bridges hunting for land:

1. Initial load at W2 (474, y, 834) — grab until inventory nearly full.
2. Walk to bridge start `(588, 68, 829)`.
3. Bot fires `/tmp/bridge_loop.sh +x 1500`.
4. If `REACHED LAND` — stop, celebrate. If `OUT OF BASALT` — walk back
   to W2 (the placed bridge is a highway; `baritone.goto 475,71,830`
   works along it), refill, walk to actual end of bridge via iterative
   hops (`baritone.goto <x_hop>,68,829`), continue.

**Hop-east pitfall:** if a hop target is past the current bridge end,
baritone will path the bot into water (it tries shorest path, water is
shortest). Symptom: bot ends up at y=62 (water surface) instead of y=68.
Recover with `baritone.goto <bot_x>,68,829` — baritone will swim bot
back up and onto bridge. Then `baritone.goto <actual_bridge_end_x>,68,829`.
Know your bridge end — the `DONE` line in the previous loop's output
says `final={"x":..,"y":68,...}`; use that x as the next hop's cap.

## Verified-good coords (update on confirmation)

| From | To | How | Last verified |
|------|-----|-----|---------------|
| Spawn (470, 76, 831) | Loot wall (567, 66, 910) | single `baritone.goto` | 2026-04-23 |
| Spawn (470, 76, 831) | W2 basalt chest (475, 71, 830) | single `baritone.goto` | 2026-04-23 |
| W2 (475, 71, 830) | East anchor (588, 68, 829) | single `baritone.goto` (surprisingly works from warehouse platform) | 2026-04-23 |
| W2 (475, 71, 830) | (587, 70, 830) | **DOES NOT WORK** — baritone descends into ocean at x~595, lands at y=62; bot drowns before it can climb back out | 2026-04-23 |

## Post-build: torch and repair the bridge (spawn-proof it)

Hostile mobs spawn at night (or in low-light pockets) on any unlit part
of the bridge. A 420-block bridge has ~3000 spawnable block tops — by
the second night this turns into a zombie/skeleton gauntlet on the bot's
walk home from mining. Lighting with torches every 8 columns brings
light level to ≥6 everywhere, which blocks hostile spawning in 1.18+.

### Ingredients

- Basalt (for repair fills): 1-5 stacks depending on how many gaps the
  widening pass left behind. One verified 420-col bridge had ~2000
  missing blocks — the initial widening pass has ~50% placement-failure
  rate due to reach/line-of-sight timing. Budget two stacks.
- Torches: target 50+ for a 420-block bridge at 8-block spacing,
  alternating walls. Each torch = 1 stick + 1 charcoal = 4 output.
- Iron armor equipped, golden_carrot in hotbar for auto-eat.

### Crafting chain (if starting with only oak_log)

```
1 oak_log → 4 planks (in 2x2 player inv grid, any one of slots 1-4)
4 planks → 1 crafting_table (2x2 pattern in slots 1-4)
8 blackstone → 1 furnace (3x3 ring pattern in crafting_table, slot 5 empty)
oak_log + oak_plank-fuel → charcoal (furnace; ~10s per smelt, ~1.5 smelts per plank fuel)
2 planks vertical → 4 sticks (crafting_table, slots 1+4 OR any vertical pair)
1 charcoal + 1 stick vertical → 4 torches (crafting_table, slots 2+5 OR 5+8)
```

Verified 2026-04-23: 128 oak_log + 64 blackstone yields a crafting table,
a furnace, 128 sticks, 28 charcoal, and 112 torches with basalt and logs
to spare.

### Repair pass script

The widen5 pass leaves gaps — repair by walking the bridge and filling
any missing floor (z=827-831 y=67) or wall (z=827, z=831 y=68) block:

```bash
# See /tmp/repair_bridge.sh for the full script
# Usage: /tmp/repair_bridge.sh <X_START> <X_END> <TORCH_SPACING>
/tmp/repair_bridge.sh 589 1007 8
```

The script walks x by 1, at each column:
1. baritone.goto (x, 68, 829) — step along the walking surface
2. place_basalt_if_air for each of 6 positions (z=828, 830, 827, 831 at
   y=67; z=827 and z=831 at y=68)
3. Every TORCH_SPACING columns, place a torch on top of the wall (side
   alternates north/south for even coverage)

Pace: ~1 column/second = ~7 minutes for a 420-column bridge.

### Common torch-placement issues

| Symptom | Cause | Fix |
|---|---|---|
| `place FAILED at (x, 69, z) — got air` | Bot not close enough when trying to place (reach distance > 4.5) | Ensure baritone.goto reached exact (x, 68, 829) before the place — the script's "couldn't reach" log line means skip, accept the gap |
| Torches at z=831 not lighting walking area (z=828-830) | Light drops 1 per block; torch on far wall leaves corridor dim | Alternate torch walls (z=827 then z=831) every other torch — the script already does this |
| Torch placed but then disappears next tick | Bot walked back through and broke it with fists accidentally | Don't baritone.goto THROUGH a just-placed torch; the repair script walks by 1 column at a time which avoids this |

## Mod-source hooks

- `BridgeTask.java` — the tick task `world.bridge` submits to. **Currently
  unreliable** on this server due to the forwardKey physics issue
  described above. Left in the tree for reference and for servers where
  `forwardKey.setPressed` actually drives movement.
- `WorldWriteHandlers.java` — the `world.bridge` RPC registration. It's
  safe to call; it just times out with `blocksPlaced:0` on this server.
- `WorldWriteHandlers.placeBlock()` — the atomic per-block place RPC the
  shell loop depends on. Keep this working — the bridge depends on the
  synthetic BlockHitResult path that bypasses the client raycast.
- `MineDownTask.java` — sibling mine-down task for reference on the
  tick-task pattern.

## When to update this skill

- Whenever a new east-edge anchor is verified (add to the "east edge"
  table).
- When the shell loop's timing changes materially (>~50%).
- When a new failure mode is seen in the wild — add a row to the
  failure-modes table with cause and fix.
- When the `world.bridge` tick task gains working `forwardKey` physics
  (e.g., a future mod version exposes a working setPressed-driven
  movement path) — swap the shell loop for the single RPC call.
