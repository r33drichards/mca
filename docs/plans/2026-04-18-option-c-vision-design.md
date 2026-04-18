# Option C — Vision Handlers Design

**Status:** Design validated 2026-04-18. Ready for implementation.

## Why

The agent driving Option C currently has structured world senses (`world.block_at`, `world.blocks_around`, `world.raycast`) but no visual input. A multimodal LLM behind the agent could reason much better with an actual rendered view of what the Minecraft client sees, plus parallel structured annotations grounding objects on screen back to RPC-callable handles.

Goal: give the agent a `world.screenshot` (and panorama variant) that returns the real framebuffer + a side-channel JSON of entities, interactive blocks, and the crosshair target — each annotation tagged with both its on-screen pixel coords AND its world coords so the agent can chain a sighting into a `container.open` / `world.interact_entity` / `baritone.goto` call.

## API

Two new methods on the existing `POST /rpc` surface.

### `world.screenshot`

```
params: {
  width?: int = 768,        // output px; height auto from window aspect
  yaw?: float,              // optional camera yaw override
  pitch?: float,            // optional camera pitch override (yaw/pitch restored after capture)
  includeHud?: bool = false,
  annotateRange?: int = 16, // world distance for interactive blocks
  format?: "png" | "jpeg" = "png"
}
returns: {
  image: <base64 string>,
  width, height,
  captured_at: <millis>,
  camera: { yaw, pitch, pos: {x,y,z} },
  annotations: {
    entities: [
      { entityId, type, name?, screen: {x,y,w,h}, world: {x,y,z}, distance }
    ],
    blocks: [
      { id, screen: {x,y}, world: {x,y,z}, distance }
    ],
    lookingAt: {                  // crosshair target
      kind: "block" | "entity" | "miss",
      // block: id, world, side, hit (sub-block hit point)
      // entity: entityId, type, world
    }
  }
}
```

### `world.screenshot_panorama`

Same params plus `angles?: int = 4` (4 = N/E/S/W; 6 = adds up/down; 8 = octants).
Returns `{ frames: [ {yaw, pitch, image, annotations}, ... ] }`. Body twitches once per frame as yaw is overridden — accepted limitation.

## Annotated kinds

**Interactive blocks** (default whitelist): chest, trapped_chest, ender_chest, barrel, furnace, blast_furnace, smoker, crafting_table, brewing_stand, anvil, beacon, hopper, dropper, dispenser, bed (any color), sign (any wood), hanging_sign, door (any wood), trapdoor, button, lever, pressure_plate, repeater, comparator, daylight_detector, jukebox, note_block, lectern, cartography_table, smithing_table, stonecutter, grindstone, loom, fletching_table, composter, cauldron.

**Entities:** all non-self, non-dead within 64 blocks that project on-screen.

## Capture mechanics

- **Thread:** OpenGL framebuffer access requires the **render thread**. All capture work runs there via `mc.execute(Runnable)`. RPC handler returns a `CompletableFuture` filled by the render-side capture; HTTP times out at 5s.
- **Frame timing:** Submit capture as a runnable to `mc.execute(...)`. It runs at next render tick (~16ms). For rotation-override: save player's current yaw/pitch, set new, defer one frame, capture, restore. Visible body twitch is acceptable for v1.
- **Framebuffer extraction:** `ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())` → `NativeImage`. Convert to bytes via `NativeImage.writeToByteArray()`. Base64-encode for JSON transport.
- **Resolution:** MC always renders at the window framebuffer size. To return at a smaller `width`, downscale the captured `NativeImage` via `Graphics2D.drawImage` with bilinear filtering on a temporary `BufferedImage`, then re-encode.
- **HUD hiding:** Toggle `mc.options.hudHidden` before capture, restore after.

## Annotation generation

Run on the same render tick as the capture so all data reflects the same world state.

**Camera matrices:** view from `Camera.getRotation()` + `Camera.getPos()`; projection from `gameRenderer.getBasicProjectionMatrix(fov)`. Compose `clip = projection × view`.

**World→screen projection:**
```
clip4 = clip · vec4(worldPos, 1)
ndc = clip4.xyz / clip4.w        // cull if clip4.w <= 0 (behind camera)
screenX = (ndc.x*0.5 + 0.5) * width
screenY = (1 - (ndc.y*0.5 + 0.5)) * height
cull if ndc.x or ndc.y outside [-1, 1]
```

**Entities:** iterate `mc.world.getEntities()`. Skip self/dead/distance>64. Project center + 8 corners of bounding box → 2D AABB.

**Interactive blocks:** iterate cube `[player.blockPos ± annotateRange]` (≤33³ ≈ 35k cells, fine). Skip air, skip if not in whitelist. Project block center.

**Crosshair target:** read `mc.crosshairTarget` (already computed by MC every tick). Map BlockHitResult / EntityHitResult / MISS into the schema.

**Caps:** entities ≤ 64 nearest, blocks ≤ 128 nearest (truncate by distance) to avoid JSON bloat in dense areas.

## File plan

- **New:** `mod-c/src/main/java/com/btone/c/handlers/VisionHandlers.java` — registers both methods, holds projection helper, entity/block annotators, framebuffer encoder. Estimated 250–350 LOC.
- **Modify:** `mod-c/src/main/java/com/btone/c/BtoneC.java` — call `VisionHandlers.registerAll(router)`.
- **No new Gradle deps.** Java's built-in `BufferedImage`, `ImageIO`, `Base64.getEncoder()` cover encoding/downscale.

## Verification

```bash
# Plain screenshot
curl ... -d '{"method":"world.screenshot"}' | jq '.result | {width, height, entities: (.annotations.entities | length), blocks: (.annotations.blocks | length), looking: .annotations.lookingAt}'

# Save image
curl ... -d '{"method":"world.screenshot","params":{"width":512}}' | jq -r '.result.image' | base64 -D > /tmp/bot-view.png && open /tmp/bot-view.png

# Look behind
curl ... -d '{"method":"world.screenshot","params":{"yaw":180}}' | jq '.result.camera'

# 4-frame panorama
curl ... -d '{"method":"world.screenshot_panorama","params":{"angles":4,"width":512}}' | jq '.result.frames | map({yaw, entityCount: (.annotations.entities | length)})'
```

## Known risks (verify in smoke test)

- **Body-twitch on yaw/pitch override** may also be sent to the server (since `player.setYaw/Pitch` mutates the client entity). If we see the in-world Bot snap mid-panorama, switch to camera-only override via `Camera.update(...)` reflection. Defer until observed.
- **Yarn class-name shifts in 1.21.8** for `ScreenshotRecorder` / `Camera` / `gameRenderer` accessors — verify against the decompiled MC sources from the existing `genSources` cache before guessing.
- **JPEG path** — Java's `ImageIO.write(image, "jpeg", out)` writes baseline JPEG. Quality default is fine for vision (~0.75). If size matters, configurable via `ImageWriteParam`.
