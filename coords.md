# Bot World — Notable Coordinates

Server: `centerbeam.proxy.rlwy.net:40387` (offline-mode Fabric server, MC 1.21.8).

## Spawn area

Bot respawns at one of two recurring spots near `(458–470, 70–78, 820–860)`. Both are on or in the spawn-protection zone where world mutation no-ops silently.

## Visited buildings

### Chest fortress (NOT the building user wants)
Location: `(467–468, 73–75, 831–834)`
Description: 4×5×3 wall of chests stacked in a small enclosed room near spawn. The bot keeps falling into this from spawn. Most chests EMPTY when opened (or container.open didn't actually fire — needs adjacent block, hard to position bot precisely).

### Wood library / study
Location: `(449, 75, 861)` ish
Description: Small wooden interior with bookshelves, lanterns, and an oak door. Visible from spawn looking SE.

### Warehouse 2 — bot's stash drop point
Location: **`(478, 71, 834)`** (overworld)
Description: Where the bot returns from the Nether portal. Near the spawn area on the same wooden platform as the chest fortress. Used as the consolidated drop-off for mined materials (basalt, blackstone, etc.). Bot prefers to deposit here before heading out for another mining run so a death only loses what's in-flight.

### Glass dome greenhouse — THE villager building
Location: `(498–554, 65–70, 919)` — large area, dome roof
Description: Massive glass-domed greenhouse. ~50+ villagers visible (Armorers, Weaponsmiths). The chest wall is INSIDE the greenhouse along the east interior wall.

#### Loot wall — armor + tools chests
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
