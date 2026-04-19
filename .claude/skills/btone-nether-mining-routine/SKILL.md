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
for m in auto-eat auto-armor auto-tool auto-weapon auto-replenish kill-aura; do
  rpc "{\"method\":\"meteor.module.enable\",\"params\":{\"name\":\"$m\"}}"
done
```

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

# 1b. Step a few blocks clear of the portal (else you trigger return transit)
P=$(rpc '{"method":"player.state"}' | jq -c '.result.blockPos')
PX=$(echo "$P" | jq -r .x); PY=$(echo "$P" | jq -r .y); PZ=$(echo "$P" | jq -r .z)
rpc "{\"method\":\"baritone.goto\",\"params\":{\"x\":$((PX+5)),\"y\":$PY,\"z\":$((PZ+5))}}"
# wait until baritone idle (active=false)

# 1c. Fire unbounded mine
rpc '{"method":"baritone.mine","params":{"blocks":["minecraft:blackstone","minecraft:basalt"],"quantity":-1}}'

# 1d. Poll until FREE <= 2 or HP=0. Print only on changes.
LAST=""
while true; do
  STAT=$(rpc '{"method":"player.inventory"}' | jq -c '{free: 36 - (.result.main | length), bs: ([.result.main[] | select(.id == "minecraft:blackstone") | .count] | add // 0), ba: ([.result.main[] | select(.id == "minecraft:basalt") | .count] | add // 0)}')
  HP=$(rpc '{"method":"player.state"}' | jq -r '.result.health')
  CUR="$STAT hp=$HP"
  [ "$CUR" != "$LAST" ] && { echo "$(date +%H:%M:%S) $CUR"; LAST=$CUR; }
  FREE=$(echo "$STAT" | jq -r .free)
  [ "$FREE" -le 2 ] 2>/dev/null && break
  [ "$HP" = "0.0" ] && break  # → goto Death recovery section
  sleep 6
done
```

## Step 2: STORE

Back to overworld, deposit at warehouse 2 (see `coords.md` for the project's
specific coords).

```bash
# 2a. Stop mining, walk to portal
rpc '{"method":"baritone.stop"}'
rpc '{"method":"baritone.get_to_block","params":{"blockId":"minecraft:nether_portal"}}'
# wait for dim == minecraft:overworld

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
  CHEST_SIZE=$([ "$NSLOT" -gt 70 ] && echo 54 || echo 27)
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
- `pickaxes < 1` OR user explicitly says durability low → grab from chest wall
- `food < 16` → restock from food barrel or craft bread
- `armor < 4` → grab from chest wall (auto-armor equips)

### 3a. Pickaxe / armor — from the chest wall

Coords in `coords.md` ("Loot wall — armor + tools chests" — typically
`(568–569, 66–68, 910–917)` on the user's test world).

```bash
rpc '{"method":"baritone.goto","params":{"x":567,"y":67,"z":911}}'
# wait
rpc '{"method":"container.open","params":{"x":568,"y":66,"z":910}}'
sleep 1.5
STATE=$(rpc '{"method":"container.state"}')

# Grab one diamond_pickaxe
SLOT=$(echo "$STATE" | jq -r '[.result.slots[]? | select(.id == "minecraft:diamond_pickaxe")][0].slot // empty')
[ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}"
sleep 0.3

# For each missing armor piece, grab one of that piece
for piece in helmet chestplate leggings boots; do
  EQUIPPED=$(rpc '{"method":"player.inventory"}' | jq -r "[.result.main[] | select(.slot >= 36 and .slot <= 39 and (.id | test(\"$piece\")))] | length")
  [ "$EQUIPPED" -gt 0 ] && continue
  SLOT=$(echo "$STATE" | jq -r "[.result.slots[]? | select(.id | test(\"diamond_$piece|iron_$piece\"))][0].slot // empty")
  [ -n "$SLOT" ] && rpc "{\"method\":\"container.click\",\"params\":{\"slot\":$SLOT,\"button\":0,\"mode\":\"QUICK_MOVE\"}}"
  sleep 0.3
done

rpc '{"method":"container.close"}'
# auto-armor module equips within ~1s
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
