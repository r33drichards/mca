---
name: btone-nether-mining-routine
description: Use when an agent driving a btone-mod-c Minecraft Bot is asked to mine resources in the Nether (blackstone, basalt, netherrack, ancient_debris, etc.) — the proven mine→store→resupply loop with survival settings, baritone gotchas, and death recovery.
---

# Nether mining routine for the btone Bot

## The loop

```
1. MINE          (in the Nether, until inventory near full)
2. STORE         (back to overworld warehouse, deposit haul)
3. RESUPPLY      (only if needed — pickaxe durability low, food < 16, armor missing)
4. goto 1
```

This is the entire steady-state. Each step expands below.

## One-time setup (run before the first iteration)

```bash
# Standard preamble — every agent shell needs this
CFG="$HOME/btone-mc-work/config/btone-bridge.json"
PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
BASE="http://127.0.0.1:$PORT"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
rpc() { curl -s -X POST "$BASE/rpc" "${H[@]}" -d "$1"; }
```

Make sure baritone is configured for safe mining and Meteor survival modules
are on. Re-run after every restart — Meteor settings persist but Baritone's
`allowBreak` resets:

```bash
rpc '{"method":"baritone.command","params":{"text":"set allowBreak true"}}'
rpc '{"method":"baritone.command","params":{"text":"set allowParkour false"}}'
# Pathfinder avoids hostile mobs more aggressively. Default coef is 1.5 / radius 8;
# bumping these means baritone routes AROUND mobs instead of mining straight at them.
rpc '{"method":"baritone.command","params":{"text":"set mobAvoidanceCoefficient 4.0"}}'
rpc '{"method":"baritone.command","params":{"text":"set mobAvoidanceRadius 16"}}'
for m in auto-eat auto-armor auto-tool auto-weapon auto-replenish kill-aura \
         no-fall auto-totem auto-respawn auto-mend anti-hunger; do
  rpc "{\"method\":\"meteor.module.enable\",\"params\":{\"name\":\"$m\"}}"
done
# safe-walk is a footgun: it freezes the bot on narrow nether platforms.
# Don't enable it broadly — toggle on only when you specifically need
# edge-sneak protection, and toggle off before pathing through tight spots.
```

### Why each Meteor module — and how to find new ones

| Module | What it does for the bot |
|---|---|
| `auto-eat` | Eats food from hotbar/offhand when food bar drops. Required for unattended runs. |
| `auto-armor` | Pulls armor pieces from main inventory into slots 36-39. The reason "grab any armor and let auto-armor equip it" works. |
| `auto-tool` | Switches to the best hotbar tool for whatever block the bot is breaking. Mines blackstone with the diamond pickaxe even if a stone pickaxe is selected. |
| `auto-weapon` | Same as auto-tool but for hostile mobs. Combined with kill-aura, the bot fights back with the best sword in hotbar. |
| `auto-replenish` | When a hotbar stack empties or a hotbar tool breaks, pulls another of the same item id from main inventory into that slot. **Lets the bot keep mining even when its first pickaxe shatters** — as long as a spare is anywhere in main inventory. |
| `kill-aura` | Auto-attacks hostile mobs in range. Pairs with auto-weapon. |
| `safe-walk` | Sneaks at edges. **Footgun in the nether** — freezes the bot on narrow platforms. Leave OFF; prefer `no-fall` instead. |
| `no-fall` | Negates fall damage entirely (sends a 0.0 velocity packet right before landing). The single biggest win for nether mining — magma-cube knockback off a y=96 ledge becomes a non-event. |
| `auto-totem` | Auto-equips a Totem of Undying in the offhand slot. If the bot has even one totem, fatal damage triggers it for a free respawn-on-the-spot. Stack a few in inventory. |
| `auto-respawn` | Automates the respawn click after death — saves the agent from having to call `player.respawn` manually. |
| `auto-mend` | If the bot is holding/wearing Mending tools/armor, exp orbs auto-repair them. Compounds well with auto-tool. |
| `anti-hunger` | Cuts hunger drain (avoids sprinting drain mostly). Combined with auto-eat, lets the bot stay topped up on much less food. |

When the bot needs a new background behavior, scan `meteor.modules.list`
for it before writing custom RPC logic:

```bash
rpc '{"method":"meteor.modules.list"}' | jq -r '.result.modules[]' | grep -i KEYWORD
```

Examples of useful searches: `tool`, `hotbar`, `replenish`, `inv`, `armor`,
`eat`, `swap`, `aura`. If a module exists, prefer enabling it over scripting
the same behavior with `container.click` / `player.inventory` polling — the
module runs every tick on the client, the script polls every few seconds.

## Step 1: MINE

Walk to the nearest portal, transit, mine until inventory is near full.

```bash
# 1a. Walk to portal (works in either dim — gets the dim-local portal)
rpc '{"method":"baritone.get_to_block","params":{"blockId":"minecraft:nether_portal"}}'
# Wait for dim flip — poll player.state until .dim == "minecraft:the_nether"
while true; do
  D=$(rpc '{"method":"player.state"}' | jq -r '.result.dim')
  [ "$D" = "minecraft:the_nether" ] && break
  sleep 3
done

# 1b. Step clear of portal — but smarter than +5/+5. We track the LAST
# productive mining spot in a state file (cleared whenever the bot dies)
# and pre-walk there. This avoids re-entering known-deadly areas on every
# cycle. Origin: bot was dying repeatedly to magma cube knockback at the
# +X ledge near the spawn portal; pre-walking far away in a known-safe
# direction sidesteps that trap.
SPOT_FILE="$HOME/btone-mc-work/.last-safe-mining-spot.json"
if [ -s "$SPOT_FILE" ]; then
  SX=$(jq -r .x "$SPOT_FILE"); SY=$(jq -r .y "$SPOT_FILE"); SZ=$(jq -r .z "$SPOT_FILE")
  echo "pre-walking to last safe spot ($SX, $SY, $SZ)"
else
  # No remembered spot — push hard in a fixed safe direction (-X here,
  # opposite from the project-specific death zone documented in coords.md).
  P=$(rpc '{"method":"player.state"}' | jq -c '.result.blockPos')
  PX=$(echo "$P" | jq -r .x); PY=$(echo "$P" | jq -r .y); PZ=$(echo "$P" | jq -r .z)
  SX=$((PX-50)); SY=$PY; SZ=$((PZ-10))
  echo "no saved spot, going -50 X to avoid the magma-cube ledge"
fi
rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$SX,\"y\":$SY,\"z\":$SZ}}"
# wait until baritone idle, then stop (so the goto doesn't fight the mine)
sleep 12
rpc '{"method":"baritone.stop"}'

# 1c. Fire unbounded mine
rpc '{"method":"baritone.mine","params":{"blocks":["minecraft:blackstone","minecraft:basalt"],"quantity":-1}}'

# 1d. Poll until inventory full / HP=0 / pickaxe broke. Print only on changes.
# Note jq syntax: separate calls per field — jq parses `(.x - 478)` as a
# parser error in some contexts; cleaner to extract each value with its own
# `jq -r` invocation.
SPOT_FILE="$HOME/btone-mc-work/.last-safe-mining-spot.json"
LAST=""
LAST_USED=0
while true; do
  USED=$(rpc '{"method":"player.inventory"}' | jq -r '.result.main | length')
  PICKS=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id | test("pickaxe"))] | length')
  HP=$(rpc '{"method":"player.state"}' | jq -r '.result.health')
  POS=$(rpc '{"method":"player.state"}' | jq -c '.result.blockPos')
  CUR="used=$USED picks=$PICKS hp=$HP pos=$POS"
  [ "$CUR" != "$LAST" ] && { echo "$(date +%H:%M:%S) $CUR"; LAST=$CUR; }

  # Save current pos as the last safe spot whenever inventory grows AND
  # the bot is well above the typical fall-death floor (y >= 80). The
  # y-floor filter is the cheap "is this area survivable" heuristic —
  # see coords.md for project-specific safe-zone notes.
  if [ "$USED" -gt "$LAST_USED" ]; then
    PY=$(echo "$POS" | jq -r .y)
    if [ "$PY" -ge 80 ] 2>/dev/null; then
      echo "$POS" > "$SPOT_FILE"
    fi
    LAST_USED=$USED
  fi

  [ "$USED" -ge 34 ] 2>/dev/null && { echo "INVENTORY FULL"; break; }
  # Reserve at least 1 pick for the journey home — break the cycle when picks
  # drops to 1, not 0. Mining empty-handed wastes durability on the LAST pick
  # if anything still needs digging on the way back to portal.
  [ "$PICKS" -le 1 ] 2>/dev/null && { echo "DOWN TO LAST PICK → store + resupply (reserve for journey)"; break; }
  if [ "$HP" = "0.0" ]; then
    # Death invalidates the saved spot — clearly not safe. The next cycle
    # will fall back to the fixed -50 X start.
    rm -f "$SPOT_FILE"
    echo "DEAD → death-recovery (cleared $SPOT_FILE)"
    break
  fi
  sleep 6
done
```

The state file lives at `~/btone-mc-work/.last-safe-mining-spot.json` (a
single `{x, y, z}` object). The contract:
- **Written** while `baritone.mine` is making progress AND the bot's y-coord
  is above the project's documented fall-death floor.
- **Read** at the start of the next MINE cycle to pre-walk before firing
  `baritone.mine`.
- **Deleted** on death so a deadly spot doesn't get re-tried.

## Step 2: STORE

Back to overworld, deposit at warehouse 2 (see `coords.md` for the project's
specific coords).

```bash
# 2a. Stop mining, walk to portal. KNOW THE PORTAL COORDS in BOTH dims
# (see coords.md "Nether portal pair") — get_to_block silently no-ops if no
# portal block is in baritone's scan range, which is easy to hit when the
# bot has wandered 100+ blocks from spawn. Use baritone.goto with explicit
# coords as a fallback so the bot can ALWAYS find its way home.
NETHER_PORTAL_X=61; NETHER_PORTAL_Y=99; NETHER_PORTAL_Z=110   # ← from coords.md (nether side)
OVERWORLD_PORTAL_X=480; OVERWORLD_PORTAL_Y=70; OVERWORLD_PORTAL_Z=858

rpc '{"method":"baritone.stop"}'
DIM=$(rpc '{"method":"player.state"}' | jq -r '.result.dim')
if [ "$DIM" = "minecraft:the_nether" ]; then
  PORTAL_GOAL="$NETHER_PORTAL_X,$NETHER_PORTAL_Y,$NETHER_PORTAL_Z"
else
  PORTAL_GOAL="$OVERWORLD_PORTAL_X,$OVERWORLD_PORTAL_Y,$OVERWORLD_PORTAL_Z"
fi
PX=$(echo "$PORTAL_GOAL" | cut -d, -f1)
PY=$(echo "$PORTAL_GOAL" | cut -d, -f2)
PZ=$(echo "$PORTAL_GOAL" | cut -d, -f3)
# Try the smart scan first (faster when in range), explicit-coord fallback
# if the bot is too far for the scanner.
rpc '{"method":"baritone.get_to_block","params":{"blockId":"minecraft:nether_portal"}}' >/dev/null
sleep 6
ACT=$(rpc '{"method":"baritone.status"}' | jq -r '.result.active')
if [ "$ACT" = "false" ]; then
  echo "get_to_block didn't engage; falling back to explicit goto ($PX, $PY, $PZ)"
  rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$PX,\"y\":$PY,\"z\":$PZ}}"
fi
# wait for dim flip

# 2b. Walk to warehouse 2 (project-specific coords from coords.md)
WH_X=478; WH_Y=71; WH_Z=834   # ← from coords.md
rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$WH_X,\"y\":$WH_Y,\"z\":$WH_Z}}"
# wait for arrival

# 2c. Find chests/barrels in radius 8 with empty space, deposit each material stack
CHESTS=$(rpc '{"method":"world.blocks_around","params":{"radius":8}}' | jq -c '.result.blocks | map(select(.id | test("chest|barrel")))')
N=$(echo "$CHESTS" | jq 'length')
for i in $(seq 0 $((N-1))); do
  # Bail if all materials gone
  LEFT=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id == "minecraft:basalt" or .id == "minecraft:blackstone")] | length')
  [ "$LEFT" = "0" ] && break

  CX=$(echo "$CHESTS" | jq -r ".[$i].x"); CY=$(echo "$CHESTS" | jq -r ".[$i].y"); CZ=$(echo "$CHESTS" | jq -r ".[$i].z")
  rpc "{\"method\":\"container.open\",\"params\":{\"x\":$CX,\"y\":$CY,\"z\":$CZ}}" >/dev/null
  sleep 1.2
  STATE=$(rpc '{"method":"container.state"}')
  NSLOT=$(echo "$STATE" | jq -r '.result.slots // [] | length')
  [ "$NSLOT" = "0" ] || [ "$NSLOT" = "null" ] && { rpc '{"method":"container.close"}' >/dev/null; continue; }
  # NSLOT only counts NON-EMPTY slots — useless for sizing. Use max slot id.
  MAX=$(echo "$STATE" | jq -r '[.result.slots[]?.slot] | max // 0')
  CHEST_SIZE=$([ "$MAX" -ge 54 ] && echo 54 || echo 27)
  # QUICK_MOVE basalt + blackstone from player slots (>= CHEST_SIZE) into chest
  for slot in $(echo "$STATE" | jq -r ".result.slots[]? | select(.slot >= $CHEST_SIZE and (.id == \"minecraft:basalt\" or .id == \"minecraft:blackstone\")) | .slot"); do
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$slot,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
    sleep 0.15
  done
  rpc '{"method":"container.close"}' >/dev/null
  sleep 0.3
done
```

If a chest fills mid-deposit, the QUICK_MOVE silently no-ops on full chest
slots. The `for $i` loop iterates to the next chest.

## Step 3: RESUPPLY (only if needed)

Check three things:

```bash
# Pickaxe durability
PICK_COUNT=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id | test("pickaxe"))] | length')
# (Note: btone-mod-c doesn't currently expose item durability via RPC.
#  Fall back to: count of pickaxes. Diamond pickaxe lasts ~1500 blocks.
#  As a heuristic, swap when bot has only 1 left and you've mined ~1k blocks
#  since last swap. The user will tell you "your pickaxe durability is low".)

# Food
FOOD_COUNT=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id | test("bread|cooked_|porkchop|apple|carrot")) | .count] | add // 0')

# Armor pieces equipped (slots 36-39)
ARMOR_COUNT=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.slot >= 36 and .slot <= 39)] | length')

echo "pickaxes=$PICK_COUNT food=$FOOD_COUNT armor=$ARMOR_COUNT"
```

Resupply triggers:
- `pickaxes < 3` (target: 1 in hand + 2 spares so a single break doesn't end the run) → grab from chest wall
- `pickaxes == 0` mid-mine — the MINE poll loop breaks on this; don't try to keep mining bare-handed
- `food < 16` → restock from food barrel or craft bread
- `armor < 4` → grab from chest wall (auto-armor equips)

### 3a. Pickaxe / armor / food — primary chest first, loot wall as fallback

Two stops, in order: the **primary gear+food chest** (project-specific
location, see `coords.md` — typically near the bot's warehouse) covers
food and most armor, and the **loot wall** is the fallback for what the
primary doesn't have (currently: diamond_pickaxe, full diamond armor set).

```bash
# --- Primary stop: gear+food chest (project coords from coords.md) ---
PRIMARY_ADJ_X=437; PRIMARY_ADJ_Y=69; PRIMARY_ADJ_Z=846
PRIMARY_X=437; PRIMARY_Y=68; PRIMARY_Z=847
rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$PRIMARY_ADJ_X,\"y\":$PRIMARY_ADJ_Y,\"z\":$PRIMARY_ADJ_Z}}"
# wait until baritone idle
rpc "{\"method\":\"container.open\",\"params\":{\"x\":$PRIMARY_X,\"y\":$PRIMARY_Y,\"z\":$PRIMARY_Z}}"
sleep 1.5
STATE=$(rpc '{"method":"container.state"}')

# Grab armor pieces missing from slots 36-39 (auto-armor will equip)
for piece in helmet chestplate leggings boots; do
  EQUIPPED=$(rpc '{"method":"player.inventory"}' | jq -r "[.result.main[] | select(.slot >= 36 and .slot <= 39 and (.id | test(\"$piece\")))] | length")
  [ "$EQUIPPED" -gt 0 ] && continue
  SLOT=$(echo "$STATE" | jq -r "[.result.slots[]? | select(.id | test(\"diamond_$piece|iron_$piece\"))][0].slot // empty")
  [ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}"
  sleep 0.3
done

# Grab a weapon (sword preferred, axe fallback). kill-aura + auto-weapon
# need at least one melee tool in the hotbar to actually fight back —
# otherwise hostile mobs just walk up and chip the bot to death.
HAS_WEAPON=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.slot < 9 and (.id | test("sword|axe")))] | length')
if [ "$HAS_WEAPON" = "0" ]; then
  STATE=$(rpc '{"method":"container.state"}')
  SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.slot < 54 and (.id | test("diamond_sword|iron_sword|stone_sword|diamond_axe|iron_axe")))][0].slot // empty')
  if [ -n "$SLOT" ]; then
    echo "grab weapon from slot $SLOT"
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
    sleep 0.3
  fi
fi

# Grab Totem of Undying if available — auto-totem keeps it in offhand and
# saves the bot from fatal damage. One totem = one free death.
HAS_TOTEM=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id == "minecraft:totem_of_undying")] | length')
if [ "$HAS_TOTEM" = "0" ]; then
  STATE=$(rpc '{"method":"container.state"}')
  SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.slot < 54 and .id == "minecraft:totem_of_undying")][0].slot // empty')
  [ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
  sleep 0.3
fi

# Grab 11+ oak_planks for the "Your Items Are Safe" mod. Each death costs
# 11 planks but preserves the bot's inventory — way better than re-running
# the full RESUPPLY loop after every magma cube hit.
PLANKS=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id | test("planks")) | .count] | add // 0')
if [ "$PLANKS" -lt 22 ]; then  # ≥2 deaths worth
  STATE=$(rpc '{"method":"container.state"}')
  SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.slot < 54 and (.id | test("planks")))][0].slot // empty')
  [ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
  sleep 0.3
fi

# Grab ONE food stack — auto-eat only takes one item per hunger event, so 32-64
# items is a long mining run's worth. Don't fill the inventory with food.
FOOD_CT=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id | test("bread|cooked_|beetroot")) | .count] | add // 0')
if [ "$FOOD_CT" -lt 16 ]; then
  SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.id | test("cooked_|bread|beetroot"))][0].slot // empty')
  [ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
  sleep 0.3
fi
rpc '{"method":"container.close"}'
sleep 0.5

# --- Backup stop: loot wall — only when primary is missing what you need ---
# Currently: diamond_pickaxe + full diamond armor set live ONLY at the loot wall.
# Skip this stop entirely if pickaxe count is OK and armor is full.
PICKS=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id == "minecraft:diamond_pickaxe")] | length')
TARGET_PICKS=3
if [ "$PICKS" -lt "$TARGET_PICKS" ]; then
  rpc '{"method":"baritone.goto","params":{"x":567,"y":67,"z":911}}'
  # wait until baritone idle, then approach the chest
  rpc '{"method":"container.open","params":{"x":568,"y":66,"z":910}}'
  sleep 1.5
  # Pull picks one at a time; chest state changes after each so re-read it.
  while [ "$PICKS" -lt "$TARGET_PICKS" ]; do
    STATE=$(rpc '{"method":"container.state"}')
    SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.id == "minecraft:diamond_pickaxe")][0].slot // empty')
    [ -z "$SLOT" ] && { echo "no more diamond_pickaxe in this chest"; break; }
    rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
    sleep 0.3
    PICKS=$(rpc '{"method":"player.inventory"}' | jq -r '[.result.main[] | select(.id == "minecraft:diamond_pickaxe")] | length')
  done
  rpc '{"method":"container.close"}'
fi
# auto-armor module equips armor within ~1s
```

### 3b. Food — from food barrels OR by baking bread

**From barrels** (project-specific coords; see `coords.md`):

```bash
rpc '{"method":"baritone.goto","params":{"x":448,"y":71,"z":855}}'
# wait, then for each food barrel:
rpc '{"method":"container.open","params":{"x":449,"y":71,"z":855}}'
sleep 1
STATE=$(rpc '{"method":"container.state"}')
CHEST_SIZE=$([ "$(echo $STATE | jq '.result.slots | length')" -gt 70 ] && echo 54 || echo 27)
# Take everything food-ish from the chest portion only
TAKEN=0
for slot in $(echo "$STATE" | jq -r ".result.slots[]? | select(.slot < $CHEST_SIZE and (.id | test(\"bread|apple|cooked_|baked_potato|carrot|melon|beef|porkchop|chicken|mutton|salmon|cod|stew|gapple\"))) | .slot"); do
  rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$slot,\"button\":0,\"mode\":\"QUICK_MOVE\"}}" >/dev/null
  sleep 0.15
  TAKEN=$((TAKEN+1))
  [ $TAKEN -ge 5 ] && break  # don't fill inventory with food
done
rpc '{"method":"container.close"}'
```

**By baking bread from hay** (when no food cache nearby): 16 hay → 9×16=144
wheat → 48 bread. Process:
1. `baritone.mine blocks=["minecraft:hay_block"] quantity=16` — needs
   `allowBreak=true` AND must be outside spawn protection.
2. Walk to a `crafting_table` block.
3. `container.open` it. Slot map: 0=output, 1-9=grid, 10-36=player main,
   37-45=hotbar.
4. `QUICK_MOVE` hay from your hotbar slot into the grid (single hay in any
   grid slot = 9 wheat output).
5. `QUICK_MOVE` slot 0 (output) repeatedly until grid empties.
6. For bread: `PICKUP` a wheat stack (cursor holds it), then right-click
   slots 1, 2, 3 (`button:1`) to place 1 wheat in each, `QUICK_MOVE` slot 0
   for 1 bread, repeat ~21 times per 64-wheat-cursor. Drop cursor remainder
   back in inventory by clicking original slot.

(Don't bake more than ~30 bread per round trip — slot pressure.)

## Hotbar discipline (do this every time you grab a tool)

Tools quick-moved from a chest land in the first free inventory slot — usually
a main-inventory slot (9-35), NOT the hotbar (0-8). The bot's auto-tool /
auto-weapon Meteor modules only swap from the *hotbar*, so a pickaxe sitting
in slot 35 is invisible to them. Symptom: `baritone.mine` reports `started`
but the bot tries to punch blackstone with bare fists.

**`auto-replenish` does the right thing for breakage** — when a hotbar
diamond_pickaxe shatters, auto-replenish finds another diamond_pickaxe in
main inventory and pulls it into the now-empty hotbar slot. Auto-tool then
selects it. So the hotbar discipline below only matters for the FIRST tool
of each kind — once any pickaxe is in slot N, future spares replenish there
automatically.

After every chest visit, pull tools into the hotbar via SWAP. SWAP requires
a container open — easiest is to open the chest you just grabbed from before
closing it, OR open any nearby chest. `button` is the *destination hotbar
slot* (0-8), `slot` is the *source inventory slot*.

```bash
# Reserve hotbar slot 6 for the spare/working pickaxe.
rpc '{"method":"container.open","params":{"x":568,"y":66,"z":910}}' >/dev/null
sleep 0.5
INV=$(rpc '{"method":"player.inventory"}' | jq -c '.result.main')
TOOL_SLOT=$(echo "$INV" | jq -r '[.[] | select(.id | test("pickaxe")) | select(.slot >= 9 and .slot <= 35)][0].slot // empty')
if [ -n "$TOOL_SLOT" ]; then
  # Container-GUI slot for player main inventory = (inv_slot - 9) + chest_size
  CHEST_SIZE=$(rpc '{"method":"container.state"}' | jq -r '[.result.slots[]? | select(.slot < 54)] | length')
  GUI_SLOT=$(( TOOL_SLOT - 9 + CHEST_SIZE ))
  rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$GUI_SLOT,\"button\":6,\"mode\":\"SWAP\"}}" >/dev/null
fi
rpc '{"method":"container.close"}' >/dev/null
```

Suggested hotbar layout the bot keeps:
- 0: sword (kill-aura uses whatever's selected, but auto-weapon swaps anyway)
- 1: food
- 2-5: free for blocks the bot collects (basalt, blackstone for bridging)
- 6: pickaxe (primary)
- 7: spare pickaxe / axe
- 8: free

Re-check after every resupply: any tool found at `slot >= 9` should be
swapped into its hotbar reservation.

## Combat interrupt — keep baritone from walking the bot to its death

`kill-aura` swings at hostile mobs in range, but Baritone keeps pathing
forward by default. Two layers of defense, in priority order:

**1. Pathfinding avoidance (preventive, set in one-time setup):**
`mobAvoidanceCoefficient=4.0` and `mobAvoidanceRadius=16` make Baritone
penalize squares near hostile mobs heavily, so it routes around them when
possible. This handles the common case (lone zombie on the path) without
any extra logic.

**2. HP-drop watcher (reactive, run alongside the MINE poll):**
For situations where avoidance isn't enough — surrounded, ambushed in a
narrow tunnel, blaze fire from above — a tiny watcher pauses Baritone
when the bot takes damage and resumes the original goal once kill-aura
clears the area:

```bash
# Run this in the background while baritone.mine is going.
# Pauses mining if HP drops by ≥1 heart between samples; resumes after 5s.
LAST_HP=20
LAST_BLOCKS='["minecraft:blackstone","minecraft:basalt"]'  # whatever you fired
while true; do
  HP=$(rpc '{"method":"player.state"}' | jq -r '.result.health // 0')
  ACTIVE=$(rpc '{"method":"baritone.status"}' | jq -r '.result.active')
  # bash can't do floats — multiply by 100 and use integer compare
  HP_X100=$(awk "BEGIN{print int($HP * 100)}")
  LAST_X100=$(awk "BEGIN{print int($LAST_HP * 100)}")
  DROP=$(( LAST_X100 - HP_X100 ))
  if [ "$DROP" -gt 200 ] && [ "$ACTIVE" = "true" ]; then
    echo "$(date +%H:%M:%S) HP $LAST_HP→$HP, pausing baritone for combat"
    rpc '{"method":"baritone.stop"}' >/dev/null
    sleep 5
    echo "$(date +%H:%M:%S) resuming mine"
    rpc "{\"method\":\"baritone.mine\",\"params\":{\"blocks\":$LAST_BLOCKS,\"quantity\":-1}}" >/dev/null
  fi
  LAST_HP=$HP
  [ "$HP" = "0" ] && break
  sleep 2
done
```

The `&` it (background) right after firing `baritone.mine`, then run the
inventory poll in the foreground. When inventory poll exits (full / dead),
kill the watcher: `kill %1`.

**Why not have kill-aura itself pause baritone?** Meteor's kill-aura runs
every client tick and doesn't know about Baritone's process queue. There's
no cross-module hook. The HP-drop watcher is the loose-coupled equivalent.

Future improvement: a `world.entities_around` RPC + a built-in
`pause-baritone-on-hostile-in-radius` mode in mod-c — would let us drop
the bash-side watcher entirely.

## Step 4: goto 1

After resupply (or skip if no resupply needed), back to MINE.

## Death recovery

Mining trips end in death often. When `player.state.health == 0.0`:

```bash
rpc '{"method":"player.respawn"}'
sleep 3
# Read death coords from chat (most recent "Death Coordinates" line)
DEATH=$(rpc '{"method":"chat.recent","params":{"n":15}}' | jq -r '.result.messages[]?' | grep -E "Death Coordinates" | tail -1)
echo "$DEATH"
# Walk to those coords if within 5 minutes (items haven't despawned)
# Walking through them auto-picks them up.

# Then re-run resupply (Step 3) to refill missing gear.
```

The bot's spawn point is sticky to the spawn-area chest fortress; even if
death was in the Nether, respawn returns to overworld spawn. After Nether
death you typically need to: respawn → walk to overworld portal → walk to
Nether portal → traverse → walk to death coords. Items expire 5 min after
drop.

If items are unrecoverable, jump straight back to RESUPPLY.

## Common failure modes (don't waste cycles)

| Symptom | Cause | Fix |
|---|---|---|
| `baritone.mine` returns `started:true` but inventory count never increases | `allowBreak=false` | `set allowBreak true` via `baritone.command` |
| Bot stops mining after a few seconds | hit a wall it won't break (in `blocksToAvoidBreaking`) or ran out of target blocks in scan range | move bot 20 blocks in some direction, re-fire mine |
| `world.mine_block` returns `started:true` but block stays | one-shot attackBlock = single tick of mining damage; survival mining needs continuous breaking | use `baritone.mine` instead |
| `container.open` returns success but `container.state` shows null/empty slots | bot too far (>~5 blocks) — open didn't actually fire server-side | `baritone.goto` adjacent, retry |
| Bot drowns at low Y in the overworld between portal and warehouse | walking through kelp forests near spawn | warehouse walk should pin Y >= 70; or set baritone `allowSwim true` and accept slower pathing |
| Bot dies in nether to "doomed to fall by Magma Cube" repeatedly at the same ledge | mob knockback at y≥80 → 30+ block fall = lethal. mobAvoidance only helps for ROUTING; once the cube is adjacent, baritone has no reaction. | Enable Meteor `safe-walk` (sneaks at edges so knockback can't push the bot off). If the same area kills repeatedly, send the bot 50+ blocks in a different direction with `baritone.goto` before firing the next `baritone.mine`. |
| Quick-move from chest leaves items in chest GUI but inventory empty | Race condition: `container.state` read before GUI populated. Sleep ≥1s after `container.open` before reading state. |

## Telemetry pattern

When polling for long ops, only print on state CHANGES so notifications
don't spam:

```bash
LAST=""
while true; do
  CUR=$(rpc ... | jq ...)
  [ "$CUR" != "$LAST" ] && { echo "$CUR"; LAST=$CUR; }
  sleep 5
done
```
