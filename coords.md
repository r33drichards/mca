# Bot World — Notable Coordinates

Server: `centerbeam.proxy.rlwy.net:40387` (offline-mode Fabric server, MC 1.21.8).

## Spawn area

Bot respawns at one of two recurring spots near `(458–470, 70–78, 820–860)`. Both are on or in the spawn-protection zone where world mutation no-ops silently.

## Visited buildings

### Wood library / study
Location: `(449, 75, 861)` ish
Description: Small wooden interior with bookshelves, lanterns, and an oak door. Visible from spawn looking SE.

### East edge of spawn island (basalt bridge anchor)
Verified 2026-04-23. The spawn island's east edge at z=823-829 is the
`(587-588, 68, z)` cut_sandstone row. Past x=589 is open ocean at water
surface y=62. Use `(588, 68, 829)` as the anchor tile when starting a
basalt bridge east — see the `btone-basalt-bridge` skill.

### Stone mine camp — east-landmass forward base
Discovered/placed 2026-04-24. The forward base for stone mining on the
east landmass, just past the bridge end. **Replaces W2 as the
deposit + resupply point** so the bot doesn't walk the 419-block
bridge each load. The camp is a 4-chest cluster + crafting_table on a
small oak-plank platform, with stone deposits a short walk into the
landmass interior.

When the user says "the stone mine camp" or "stone mine chests" they
mean this location. Bot stand: `(1014, 69, 827)`.

Cluster layout (all at z=826, north side of an oak-plank platform):
- **(1012-1013, 69, 826)** — bottom **LOOT** double-chest (verified
  2026-04-24). Treasure trove: 1× elytra, 3× totem_of_undying,
  48× ender_pearl, 128× golden_carrot, 58× iron_ingot, 64× oak_log,
  120× oak_planks, 49× firework_rocket, 191× bone_meal, 1728× poppy.
  **Don't dump stone here** — keep loot intact. Use as primary
  resupply (food + iron_ingot for picks + ender_pearls).
- **(1012-1013, 70, 826)** — top **MINING SUPPLY** double-chest
  (user-stocked 2026-04-24): 14× iron_pickaxe, 601× cobblestone,
  1× dirt. **Take picks from here** when stone-mining inventory runs
  low; saves the 419-block bridge walk back to the loot wall.
- **(1014-1015, 69, 826)** — bottom EMPTY double-chest. **Stone drop
  target.**
- **(1014-1015, 70, 826)** — top EMPTY double-chest. **Stone drop
  target overflow.**
- **(1011, 69, 828)** — crafting_table (for re-crafting picks on-site
  if iron_pickaxe + iron_ingot supply runs low).
- **(1012-1015, 69-71, 829)** — **second chest cluster** (discovered
  2026-04-24 via DEPOSIT_FALLBACK_SCAN when DROP+OVERFLOW filled). Six
  chests at z=829 (3 blocks south of the original wall): `(1012, 70, 829)`,
  `(1013, 70, 829)`, `(1013, 71, 829)`, `(1014, 69, 829)`, `(1014, 70, 829)`,
  `(1014, 71, 829)`. The first one had space and absorbed 256 cobble in
  one QUICK_MOVE pass. Use these as **secondary cobble drop** when the
  z=826 chests fill up.

Bot stand position to interact: **`(1014, 69, 827)`** (one tile south
of the chest wall, facing north). `baritone.goto 1014 69 827` from the
bridge east end (`1007 68 829`) gets there in ~3 seconds.

#### Camp farm — wheat + beetroot plot
Discovered 2026-04-24 via `baritone farm` from the camp stand. ~7×8
tilled farmland plot **just north of the camp**: spans
`x=1001–1007, z=818–825, y=69` (crops above farmland at y=68).

- **Crops:** mixed wheat + beetroots on farmland (intermixed, not
  separate rows). Auto-replants when seeds are in bot inventory.
- **Drive it:** stand near the camp and run
  `bin/btone-cli baritone.command --params '{"text":"farm"}'`. Baritone
  finds the plot and harvests/replants automatically.
- **Yields per partial pass (verified 2026-04-24):** ~10 wheat,
  ~9 beetroot, ~12 each of wheat_seeds + beetroot_seeds before the
  user interrupted ~half-done. A full harvest is ~20-25 of each crop.
- **Where to deposit:** top OVERFLOW chest at `(1014–1015, 70, 826)` —
  it already has wheat (slot 3) and now stockpiles wheat + beetroot +
  seeds.

### Basalt bridge to east landmass
Built 2026-04-23. A 419-block basalt bridge spans open ocean from the
spawn island to a copper-ore landmass east. **Fully 5-wide as of
2026-04-24** — floor at z=827–831 y=67, walls at z=827 + z=831 y=68.
Walking surface y=68 along center column z=829. Verified end-to-end
repair pass (1007 → 589) on 2026-04-24 added 52 cobble blocks where
the prior widen5 pass had gaps; also fills are cobble (not basalt) on
the patched columns.

- **Bridge west start (on spawn island):** `(588, 68, 829)` —
  cut_sandstone tile at the island's east edge. Bot walks ONTO this
  tile from the island before stepping east onto the basalt bridge.
- **Bridge east end / first placed basalt:** `(589, 67, 829)` (floor y=67).
  Walking surface `(589, 68, 829)`.
- **Bridge last placed basalt:** `(1007, 67, 829)` (floor y=67).
  Walking surface `(1007, 68, 829)`.
- **East landing (natural land):** `(1008, 67, 829)` — **copper_ore**,
  top of a new landmass east of spawn. Agents can use this as a
  nether-portal-free overworld exit to the east.

Traveling the bridge: `baritone.goto 588 68 829` from the island, then
`baritone.goto 1007 68 829` to walk the full length. Each end hop is
~5 seconds on this server.

Bridge built across two W2 basalt resupply cycles (one 6-stack load
from the (474, 71, 830) chest, one 24-stack load from the real basalt
chest at (474, 71, 834)).

### W2 basalt chest column (REAL location)
Verified 2026-04-23. The (474, 71, 830) chest I previously referenced is
now **blackstone + netherrack only**. Basalt is at the column
`(474, y=71–73, 834)`:
- y=71: ~1792 basalt (primary)
- y=72: ~1323 basalt (secondary)
- y=73: ~64 basalt (nearly empty)

Bot stands at `(475, y=71–73, 834)` facing west (yaw=90) to interact.

### W2 bot-placed crafting setup (for torches/tools)
Placed 2026-04-23 on the W2 oak_plank platform:
- `(476, 71, 830)` — **crafting_table** (bot-placed). Opens a 3x3 grid
  for recipes like furnace, torch, sticks. Bot stands at (475, 71, 830)
  facing east (yaw=-90) to interact.
- `(476, 71, 829)` — **furnace** (bot-placed from 8 blackstone). Smelts
  oak_log → charcoal at ~1 item per 10s (200 ticks), uses oak_planks
  as fuel (1 plank ≈ 1.5 smelts). Bot stands at (475, 71, 829) facing
  north to interact.

If either is missing, re-craft per the `btone-stone-mining` skill's
"Torch the bridge" appendix — recipe: 1 oak_log → 4 planks; 2 planks →
4 sticks; 8 blackstone → 1 furnace; 4 planks (2x2) → 1 crafting_table;
1 stick + 1 charcoal (vertical) → 4 torches.

### W1 wood/coal stash (REAL location)
Verified 2026-04-23. The center-row chests have **oak_log**, not birch
(coords.md previously listed birch — update):
- `(460, 73, 831)`: 256 oak_log (+ 1506 iron_block, 110 white_wool)
- `(460, 74, 831)`: 787 oak_log (+ 1927 iron_block, 9 cod)
- `(460, 72, 831)`: 0 oak_log (+ 502 iron_block, 64 white_wool, 1 sand)

Total oak_log available: ~1043. **No coal in W1 center row** —
charcoal must be smelted from oak_log in a furnace. No coal found in
lmoik's chest, loot wall chests, or W2 chest either.

### Nether portal pair
- Overworld portal: **`(480, 70, 858)`** — what `baritone.get_to_block minecraft:nether_portal` walks to from the spawn area.
- Nether-side portal: **`(61, 99, 110)`** — bot exits here. **WARNING:** the area to +X (toward `(74, 58, 114)`) is the recurring magma-cube fall-death zone. Always pre-walk `-X` 50+ blocks before firing `baritone.mine`. See the routine's last-safe-spot mechanism for details.

### Warehouse 1 — chest fortress near spawn (PRIMARY food source + IRON/WOOD stash)
Location: **`(454–463, 71–76, 825–836)`** — massive chest cluster, ~270 chests across walls. Bot interacts at the perimeter (e.g. `(458, 71, 833)`).
Adjacent structures inside: ender_chests at `(461, 71, 832–833)` and `(461, 72, 832)`, crafting_table at `(462, 70, 840)`.
Description: This was previously misidentified as a "chest fortress" (deleted entry). It IS Warehouse 1. Wide variety of items expected here — primary place to find FOOD when Warehouse 2 / lmoik's chest are depleted. Also notable: **rotten_flesh** stockpiles work fine as bot food now (auto-eat blacklist updated to allow it 2026-04-18).

**Structure:** Warehouse 1 has THREE rows of chests along X: row 1 at x=454–455 (west wall), row 2 (CENTER aisle) at x=459–460, row 3 at x=462–463 (east wall). The **center row (x=459–460)** holds the crafting stockpiles — iron_block, birch_log, etc. The east/west walls are general storage.

#### Iron + wood stash — center row, z=831 (for crafting iron pickaxes)
**Verified 2026-04-19.** Three stacked double-chests, each spans x=459–460 (opening either half shows both):

- **`(459-460, 72, 831)`** — 532 iron_block + 84 birch_log (mixed with sand, wool).
- **`(459-460, 73, 831)`** — 1508 iron_block + 466 birch_log.
- **`(459-460, 74, 831)`** — 1927 iron_block + 787 birch_log (biggest).

Total stash: **~3967 iron_block + 1337 birch_log** across the three chests — enough for thousands of iron pickaxes. Recipe: 1 iron_block → 9 iron_ingot; 1 iron_pickaxe = 3 iron_ingot + 2 sticks. 1 birch_log → 4 planks → 8 sticks (via 2 planks → 4 sticks).

### Fisherman — fishing rod stash (NOT warehouse 1)
Location: **`(412–414, 62–63, 845–847)`** — basement-level building west of Warehouse 2.
Description: Two double-chests packed with fishing rods (54+ rods each chest at `(412, 62, 846)` and `(412, 62, 847)`). A crafting table sits at `(414, 63, 845)`. Identified during the search for Warehouse 1; turns out this is the fisherman's storage, not the warehouse. Useful if the bot ever needs a fishing rod (food source via fishing) but otherwise skip — no armor / weapons / picks / cooked food here.

### Warehouse 2 — bot's stash drop point
Location: **`(478, 71, 834)`** (overworld)
Description: Where the bot returns from the Nether portal. Near the spawn area on the same wooden platform as the chest fortress. Used as the consolidated drop-off for mined materials (basalt, blackstone, etc.). Bot prefers to deposit here before heading out for another mining run so a death only loses what's in-flight.

### Gear & food chest (lmoik's, near spawn) — PRIMARY resupply
Location: **`(437, 68, 847–848)`** — single double-chest. Bot stands at `(437, 69, 846)` to interact.
Contents (snapshot 2026-04-18):
- Food: cooked_cod x64+ (multiple stacks), cooked_salmon x64+, beetroot x64+ (lots, ~10+ stacks total)
- Iron armor: 9× iron_chestplate, 6× iron_leggings, plus golden_boots/golden_leggings, chainmail_leggings
- Diamond armor: 1× diamond_helmet (slot 37). NO diamond_chestplate/leggings/boots here — fall back to loot wall for those.
- Tools: 11× stone_pickaxe (junk-tier), 3× iron_shovel, 1× diamond_axe. **No diamond_pickaxe** — keep loot wall as the pickaxe source.
- Other junk: leather pieces

Use as the default RESUPPLY stop because it's right next to the warehouse + nether-portal area. Skip to the loot-wall backup only when this chest is missing what you need (currently: diamond_pickaxe, full diamond armor set).

### Glass dome greenhouse — THE villager building
Location: `(498–554, 65–70, 919)` — large area, dome roof
Description: Massive glass-domed greenhouse. ~50+ villagers visible (Armorers, Weaponsmiths). The chest wall is INSIDE the greenhouse along the east interior wall.

#### Loot wall — armor + tools chests (BACKUP — only chest with diamond_pickaxe)
Location: **`(568–569, 66–68, 910–917)`** — two-sided double-chest wall, accessible from either x=566 (west) or x=571+ (east).
Contents (snapshot 2026-04-18):
- Diamond armor (helmet, chestplate, leggings, boots) — 1+ of each, plus iron and chainmail variants
- Diamond pickaxe x7, diamond axe, diamond shovel, diamond hoe x6
- Stone pickaxe x20+, stone hoe x10+ (junk-tier filler)
- Iron nuggets, gold ingots, charcoal
- Specific food drop: porkchop x3 at `(568, 68, 912)` slot 0

To loot tactically (avoiding "take everything" spam): open one chest, grab one of each gear class via `container.click slot=N mode=QUICK_MOVE`, close, move on. Slots 0–53 are the chest in a double-chest GUI; slots 54–89 are the player's inventory (don't quick-click those — they'd send TO the chest).

### Multi-story plant-wall building (CANDIDATE — pending visit)
Direction from spawn `(458, 78, 822)`: yaw 180 (north).
Description: 4-5 story tall building with green plant walls hanging on every floor — very distinctive. 54 entities annotated in that direction.

### Wooden ship / dock structure (CANDIDATE — pending visit)
Direction from spawn: yaw 90 (west). Over the water.
Description: Wooden building with green wave-pattern roof, looks like a ship/boat or harbor structure.

### Stone tower + cottages (CANDIDATE — pending visit)
Direction from spawn: yaw 0 (south).
Description: Tall stone-brick tower with trees on it, plus a small wooden cottage and a third unidentified structure. 2 villagers visible.

## Hazards

- **Spawn protection** at coordinates roughly `(440–470, 60–90, 820–870)` — block break/place silently no-ops.
- **Water at low Y** around `(630, 56, 912)` — bot drowned here once.
- **Zombies** spawn at night near `(556, 66, 915)`.
- **"Your Items Are Safe" mod** is installed but requires planks (11 per death) to actually save items — bot has none, so death = inventory loss.

## Settings tuned for the bot

- `pauseOnLostFocus = false` (set by btone-mod-c at CLIENT_STARTED)
- Baritone `allowBreak = false`, `allowParkour = false` (don't dig through walls or risk parkour)
- Meteor `auto-eat` enabled — auto-eats food when hungry
- Meteor `kill-aura` enabled — auto-attacks hostile mobs in range
- Meteor `auto-armor` enabled — auto-equips best armor from inventory
  (this was the magic for getting the diamond gear ONTO the bot — picked up
  6 pieces via container.click QUICK_MOVE, then enabled auto-armor and the
  4 armor pieces moved to slots 36/37/38/39 within seconds)
