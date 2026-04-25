# Stone-mine loop — one iteration

Use with `/loop <interval> bin/stone-mine-loop.md`. Each tick: read state,
decide which phase to drive, execute one chunk, exit. The next tick picks
up wherever the bot is.

## Constants

- **Camp stand:** `(1014, 69, 827)` — bot's home position.
- **Camp plot (farm):** `(1001-1007, 69, 818-825)` — wheat + beetroots.
- **Camp chests:**
  - LOOT bottom `(1012-1013, 69, 826)` — oak_planks, golden_carrots, iron.
  - SUPPLY top `(1012-1013, 70, 826)` — cobble + iron_pickaxes.
  - DROP bottom `(1014-1015, 69, 826)` — fills first with mined cobble.
  - OVERFLOW top `(1014-1015, 70, 826)` — secondary cobble dump + crops + seeds.
  - Crafting table `(1011, 69, 828)`.

## Settings (verify once per session)

```bash
# Each setter is idempotent — safe to re-run every iteration.
bin/btone-cli baritone.command --params '{"text":"set legitMine true"}'
bin/btone-cli baritone.command --params '{"text":"set freeLook false"}'
bin/btone-cli baritone.command --params '{"text":"set randomLooking 0"}'
bin/btone-cli baritone.command --params '{"text":"set allowPlace false"}'

# Meteor auto-eat thresholds — defaults of 16/10 leave the bot stuck in
# a no-regen deadband (food=17 with HP<20). Bump both so HEAL phase
# actually heals.
bin/btone-cli meteor.module.setting_set --params '{"name":"auto-eat","setting":"hunger-threshold","value":"18"}'
bin/btone-cli meteor.module.setting_set --params '{"name":"auto-eat","setting":"health-threshold","value":"18"}'
bin/btone-cli meteor.module.enable --params '{"name":"auto-weapon"}'

# run-away-from-danger: triggers a flee path when HP drops sharply or a
# hostile mob is in range. Without it the bot tanks zombies until dead —
# the most common failure mode this session was "Bot was slain by Zombie"
# while baritone kept mining at HP 6.
bin/btone-cli meteor.module.enable --params '{"name":"run-away-from-danger"}'

# auto-replenish: with search-hotbar=true the module refills hotbar slots
# from main inv when their count hits min-count. This is the meteor-side
# replacement for the ENSURE_PICK_IN_HOTBAR / ENSURE_FOOD_IN_HOTBAR
# manual SWAPs — once an item is seeded on the hotbar (cold-start), the
# module keeps it stocked.
bin/btone-cli meteor.module.setting_set --params '{"name":"auto-replenish","setting":"search-hotbar","value":"true"}'

# surface: when underwater air drops below threshold, holds jump to rise
# (and cancels baritone path while rising). Source lives in
# mod-c/src/main/java/com/btone/c/meteor/Surface.java — needs a JAR
# rebuild + redeploy + MC restart to actually take effect. Until then
# this call returns `no_module:surface` and the SURFACE_FROM_DRAIN phase
# in the decision table is the fallback (detects y<62 and fires
# baritone.goto y=65). The enable call is idempotent + safe to leave in.
bin/btone-cli meteor.module.enable --params '{"name":"surface"}'
```

## Iteration logic

```
1. Read state:  pos, hp, food, baritone.active, inventory counts
2. Pick phase based on state — see decision table below
3. Drive one chunk for that phase
4. Return; let /loop fire again
```

### Decision table

Evaluate top-to-bottom; first match wins.

| Condition | Phase | Action |
|---|---|---|
| `hp == 0` | DEAD | `player.respawn`; next tick walks back to camp |
| `hp < 12 && pos != camp` | SAFETY_RETREAT | `baritone.command "stop"`; `set allowPlace true`; `baritone.goto camp`. Bare HP gate trumps RUNNING — do NOT let baritone keep mining at low HP. |
| `hp < 18 && pos == camp` | HEAL | open LOOT chest `(1012, 69, 826)`, QUICK_MOVE any food slot if no food in inv (verified 2026-04-24: `bread` at slot 16 ×20; coords.md's "golden_carrot" claim is stale); auto-eat consumes from inv; wait — natural regen needs food ≥ 18. Skip MINE this tick. |
| `food_in_inventory < 5 && pos == camp` | PULL_FOOD | mining without food is a death spiral — auto-eat can't fire, no manual fallback. Keep at least 5 food units of inventory at all times. Resolution order: (1) Open LOOT chest `(1012, 69, 826)`, QUICK_MOVE any food (bread, golden_carrot, cooked_cod, cooked_salmon, beetroot, melon_slice). (2) If LOOT has none, open OVERFLOW chest `(1014, 70, 826)` — pull wheat (≥3) and bake bread at camp crafting table (3 wheat horizontal → 1 bread). (3) If OVERFLOW also has neither food nor wheat, FARM the camp plot (`baritone.command "farm"`) — yields wheat + beetroot in ~3 min, replants seeds. Skip MINE this tick. |
| `picks > 0 && no pickaxe in hotbar slots 0-8` | ENSURE_PICK_IN_HOTBAR_COLDSTART | one-shot cold-start fix: after a death/respawn the hotbar is empty and auto-replenish has no template to follow. Manually SWAP one main-inv pick onto the held slot. Once a pick is seeded on the hotbar, `auto-replenish` (with `search-hotbar=true`) keeps that slot full mid-trip — this rule only fires after a fresh respawn or first session boot. |
| `baritone.active == false && food_in_inventory > 0 && no food in hotbar slots 0-8` | ENSURE_FOOD_IN_HOTBAR_COLDSTART | one-shot cold-start fix, same logic as above but for food. SWAP a bread stack onto a non-held hotbar slot. After seeding, `auto-replenish` keeps the slot stocked from main inv. |
| `chat.recent contains "danger" / "running" / "fleeing" since last tick OR pos moved >5 blocks AWAY from baritone goal in last tick` | DANGER_FLEE_DETECTED | the `run-away-from-danger` meteor module just hijacked control. Don't fight it — log the event, cancel any baritone goal that points back into the danger zone, mark next phase as RETURN_AFTER_FULL once HP recovers ≥18 (so we land at camp, not deeper). |
| `pos.y < 62 AND pos NOT on bridge centerline (z != 829)` | SURFACE_FROM_DRAIN | meteor has no `surface` module on this build, so the loop fakes it: bot is in oceanic water below sea level (y<62) and not on the basalt bridge (which sits at y=68 z=829). Fire `baritone.goto pos.x, 65, pos.z` to swim straight up. Track in chat: drowning damage shows as `Bot drowned` with low-HP heartbeat ticks. |
| `baritone.active == true` | RUNNING | nothing — let the in-flight command finish |
| `picks == 0 && cobble_in_inv >= 27 && sticks_in_inv >= 18` | CRAFT | open crafting table, batch-craft picks |
| `picks == 0 && cobble_in_inv < 27` | RESTOCK_COBBLE | open SUPPLY chest, QUICK_MOVE 1 cobble stack |
| `picks == 0 && sticks_in_inv < 18` | RESTOCK_PLANKS_AND_CRAFT_STICKS | pull oak_planks (or oak_log → 4 planks each) from camp LOOT chest `(1012, 69, 826)`. Craft sticks: 2 planks vertical → 4 sticks; 9 planks → 18 sticks. If LOOT has neither planks nor oak_log, walk to **Warehouse 1** for the wood resupply (next row). |
| `picks == 0 && sticks_in_inv < 18 && camp LOOT has no planks AND no oak_log` | RESUPPLY_WOOD_FROM_W1 | camp's wood is depleted. Two-hop walk via baritone to Warehouse 1 wood stash (verified 2026-04-23): `goto 1007 68 829` (bridge east end), `goto 588 68 829` (spawn-island east edge), `goto 458 71 833` (W1 perimeter stand). Open the center-row top chest at `(460, 74, 831)` — the biggest oak_log stash (787 logs verified) — and QUICK_MOVE 1 stack (64 oak_logs) into bot inventory. Then reverse-hop home: `goto 588 68 829` → `goto 1007 68 829` → `goto 1014 69 827`. Once back at camp, RESTOCK_PLANKS_AND_CRAFT_STICKS resumes naturally (logs in inv → craft planks → sticks). Round trip ~5-7 min. While here, optionally top up the camp LOOT chest with extra logs to amortize future trips. |
| `pos.y < 65 && picks > 0` | DEEP — abort if user signals stop, else keep mining |
| `pos != camp && cobble >= 256 && picks > 0` | RETURN_AFTER_FULL — `set allowPlace true; baritone.goto camp; set allowPlace false` |
| `pos == camp && cobble_in_inv >= 64 && DEPOSIT to DROP/OVERFLOW didn't reduce inv` | DEPOSIT_FALLBACK_SCAN | the standard chests are full; do NOT bypass DEPOSIT. Scan within radius 8 of the bot for other `minecraft:chest` blocks via `world.blocks_around`. For each chest coord (deduped — a double-chest reports two coords for the same inventory; opening either side opens the same screen), try `container.open` + look at chest-side slots for `minecraft:cobblestone` slots that aren't full (count < 64) OR fully empty slots (slot<54 and id=="minecraft:air"). If at least one such slot exists, QUICK_MOVE cobble. Skip the LOOT chest at `(1012, 69, 826)` (don't pollute treasure) and the SUPPLY chest at `(1012, 70, 826)` (don't pollute pick supply). |
| `pos == camp && cobble_in_inv >= 64 && DEPOSIT_FALLBACK_SCAN failed (no nearby chest had room)` | DEPOSIT_AT_W2 | last-resort: cart cobble to Warehouse 2 (the spawn-island stash). Two-hop walk via baritone (single-hop into the ocean drowns the bot — verified): `goto 1007 68 829` (bridge east end), `goto 588 68 829` (spawn-island east edge), then `goto 475 71 830` (W2 platform). Deposit into the empty double-chest at `(475, 71, 831)` (verified empty 2026-04-24, took 256 cobble). Then reverse-hop home: `goto 588 68 829` → `goto 1007 68 829` → `goto 1014 69 827`. Round trip is ~5 min one-way at baritone pace, so this is expensive — prefer building a new DROP chest on top of the camp stack first (8 oak_planks → minecraft:chest, place at `(1014, 71, 826)` via `world.place_block side=up` from a tile adjacent at y=70). |
| `pos == camp && cobble_in_inv >= 64` | DEPOSIT — open DROP chest, QUICK_MOVE all cobble; fall back to OVERFLOW |
| `pos == camp && wheat>0 OR seeds>0` | DEPOSIT_CROPS — open OVERFLOW, QUICK_MOVE wheat/seeds; KEEP beetroot |
| `pos == camp && food < 14 && plot_has_grown_crops` | FARM — `baritone.command "farm"` |
| `pos == camp && surplus_in_inventory && picks > 0` | PRE_MINE_DEPOSIT_SURPLUS | last phase before each MINE — leave non-trip-essential items in chests so a death doesn't lose them. **Trip-essential keep set:** ≤9 stone_pickaxes, ≤8 bread, ≤1 stack cobble, 1 iron_sword, totem (offhand), ≤6 sticks, ≤4 planks (mid-trip stick re-craft buffer). **Deposit everything else** before mining: extra picks, all wheat + all seeds (wheat_seeds, beetroot_seeds, melon_seeds) → OVERFLOW; all oak_log → LOOT; extra cobble → DROP/OVERFLOW; junk (stone_hoe, dirt, raw_copper, andesite, granite, gunpowder, smooth_basalt) → OVERFLOW. Re-evaluate decision table next tick. |
| `pos == camp && all above false && picks > 0` | MINE — `baritone.command "mine 1000 minecraft:stone"` |

### Module trigger monitor (every tick)

Each tick should `chat.recent` and grep for these prefixes — they're emitted by mod-c custom Meteor modules. Surface them in the tick's user-facing log line so the operator knows when survival auto-magic fired:

| Prefix | Module | What it means |
|---|---|---|
| `[ensure-pick]` | `EnsurePickInHotbar.java` | swapped a main-inv pickaxe onto the held slot — usually right after a death/respawn or auto-tool selecting away |
| `[ensure-food]` | `EnsureFoodInHotbar.java` | swapped a food stack onto a non-held hotbar slot |
| `[surface]` | `Surface.java` | `triggered: rising` (low air) or `released` — bot is/was swimming up. Don't fight with `baritone.goto` while this is active; module cancels baritone's pathing on its own |
| `[run-away]` | `RunAwayFromDanger.java` | `triggered: mob-in-range` or `triggered: hp-drop` — flee path engaged. Same DANGER_FLEE_DETECTED handling as before |

Quick scan command — **scan the latest launch log**, NOT `chat.recent`. Meteor module `info()` calls land in the client-side chat HUD via `ClientChatHud.addMessage` and never pass through `ClientReceiveMessageEvents.GAME`, so mod-c's `chat.recent` returns empty for them. The launch log captures stdout/stderr from the JVM and DOES include Meteor's chat output:

```bash
LATEST_LOG=$(ls -t ~/btone-mc-work/launch-*.log | head -1)
tail -200 "$LATEST_LOG" | grep -E '\[(ensure-pick|ensure-food|surface|run-away)\]' | tail -3
```

If any line is fresh (timestamp newer than the last tick's marker), echo it in the user-facing reply for the tick. Track the last-seen line by stashing the most recent timestamp prefix (e.g. `[22:48:15]`) in a temp file like `/tmp/btone-loop-last-trigger-ts` and only echo lines whose timestamp is strictly greater.

A real fix is to add a Mixin into `ClientChatHud.addMessage` (or a `ChatHudCallback`) that pushes every received message — including Meteor's client-side `info()` output — into mod-c's existing chat buffer. Then `chat.recent` would surface module triggers and this routine could go back to using it. Tracked as a TODO in `BtoneC.java` near the `ChatHandlers` registration.

### Failure-mode shortcuts

- **`baritone.mine` deadlocks the client thread** — never call it. Always use `baritone.command "mine ..."`.
- **Bottom DROP chest silently fills up** — after every deposit, recount inventory; if cobble still > 0, retry against OVERFLOW.
- **Climb-out fails without `allowPlace`** — bot pillars with cobble. Re-enable for the goto, restore false after arriving.
- **Single-hop W2 → spawn-island east edge drops bot in ocean at x≈595** — use two hops via `(588, 68, 829)` if returning from W2.
- **`baritone.command` is the only reliable mine path** — `baritone.mine` deadlocks the client thread on this build.

## Quick recipes

- **Per pickaxe:** 3 cobble + 2 sticks. 9 picks = 27 cobble + 18 sticks.
- **Sticks:** 2 planks vertical (grid slots 1+4) → 4 sticks. 4 planks → 8 sticks.
- **Crafting screen slot map (`class_479`):**
  - 0 = output, 1-9 = 3x3 grid (top row 1,2,3; mid 4,5,6; bot 7,8,9)
  - 10-36 = main inv (player slot 9 = screen 10)
  - 37-45 = hotbar (player slot 0 = screen 37)
- **Stone pick recipe layout:** cobble in slots 1, 2, 3; sticks in slots 5, 8.
- **CRAFT pitfall (verified 2026-04-24 the hard way):** if cobble is split across multiple inventory slots and the FIRST slot has <27 items, the right-click-distribute pattern only fills slots 1+2 of the grid → recipe accidentally matches **stone_hoe** (cobble cobble + stick stick = hoe) and you get 9 useless hoes. Fix: before distributing, *consolidate cobble onto one slot* (or repeatedly PICKUP from successive cobble source slots until cursor ≥ 27). Always verify the pre-output grid shows cobble in slots 1, 2, AND 3 before shift-clicking output.

## Per-iteration target

Aim for one bounded action per tick — *don't* chain mine→return→deposit→craft in one tick. Let the loop interval do the pacing; ticks should be cheap and state-aware.

A typical full cycle takes:
- MINE 1000 stone — ~13-15 min, ~9 picks consumed
- RETURN climb — ~1-2 min
- DEPOSIT — ~10 s
- RESTOCK + CRAFT — ~30 s
- FARM (every 3-4 mine cycles) — ~3 min

Net rate at steady state: ~60 cobble/min including overhead.
