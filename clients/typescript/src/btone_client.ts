// btone — JSON-RPC client for btone-mod-c.
//
// GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
// Run python3 bin/generate-clients.py to regenerate after mod schema changes.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface BtoneConfig {
  port: number;
  token: string;
}

export class BtoneError extends Error {
  constructor(public code: string, public message: string, public raw: any) {
    super(`${code}: ${message}`);
  }
}

export class BtoneClient {
  private url: string;
  private token: string;
  public timeoutMs: number;

  constructor(opts: {
    host?: string;
    port?: number;
    token?: string;
    configPath?: string;
    timeoutMs?: number;
  } = {}) {
    let port = opts.port;
    let token = opts.token;
    if (port === undefined || token === undefined) {
      const path = opts.configPath ?? join(homedir(), "btone-mc-work", "config", "btone-bridge.json");
      const cfg: BtoneConfig = JSON.parse(readFileSync(path, "utf8"));
      port = port ?? cfg.port;
      token = token ?? cfg.token;
    }
    this.url = `http://${opts.host ?? "127.0.0.1"}:${port}/rpc`;
    this.token = token;
    this.timeoutMs = opts.timeoutMs ?? 30_000;
  }

  /** Low-level call. Use typed methods below for everyday work. */
  async call<T = any>(method: string, params?: object): Promise<T> {
    const body: any = { method };
    if (params !== undefined) body.params = params;
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), this.timeoutMs);
    try {
      const resp = await fetch(this.url, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${this.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      const env = await resp.json();
      if (!env.ok) {
        const err = env.error ?? {};
        throw new BtoneError(err.code ?? "unknown", err.message ?? "", env);
      }
      return env.result as T;
    } finally {
      clearTimeout(t);
    }
  }

  // ---------- typed methods (auto-generated) ----------
  /** Echo whatever params were sent. Useful for transport tests. */
  async debugEcho(params?: object): Promise<any> {
    return this.call("debug.echo", params);
  }

  /** List all registered RPC method names. */
  async debugMethods(): Promise<any> {
    return this.call("debug.methods");
  }

  /** OpenRPC self-introspection. Returns the full schema of this service. */
  async rpcDiscover(): Promise<any> {
    return this.call("rpc.discover");
  }

  /** Read player position, rotation, health, food, dimension. */
  async playerState(): Promise<any> {
    return this.call("player.state");
  }

  /** Read full main inventory + hotbar selected slot. */
  async playerInventory(): Promise<any> {
    return this.call("player.inventory");
  }

  /** Read items in main + off hand. */
  async playerEquipped(): Promise<any> {
    return this.call("player.equipped");
  }

  /** Click respawn after a death; closes the death screen. */
  async playerRespawn(): Promise<any> {
    return this.call("player.respawn");
  }

  /** Pillar straight up to a target Y by holding jump+use. Block id must be in hotbar. */
  async playerPillarUp(params?: object): Promise<any> {
    return this.call("player.pillar_up", params);
  }

  /** Walk-and-place bridge in a horizontal direction (forward+sneak+use loop). DEPRECATED: see world.bridge. */
  async playerBridge(params?: object): Promise<any> {
    return this.call("player.bridge", params);
  }

  /** Walk-and-place ascending stairs in a horizontal direction. */
  async playerStairsUp(params?: object): Promise<any> {
    return this.call("player.stairs_up", params);
  }

  /** Force player head + body yaw/pitch. Stomps last* fields so the renderer doesn't interpolate back. */
  async playerSetRotation(params?: object): Promise<any> {
    return this.call("player.set_rotation", params);
  }

  /** Press or release a vanilla input key (jump/sneak/sprint/attack/use/forward/back/left/right). */
  async playerPressKey(params?: object): Promise<any> {
    return this.call("player.press_key", params);
  }

  /** Force-set client-side position. Server may snap back on strict anti-cheat. Stuck-pocket escape only. */
  async playerTeleport(params?: object): Promise<any> {
    return this.call("player.teleport", params);
  }

  /** Set player velocity vector (ticks-per-block units). Useful for fall-recovery. */
  async playerSetVelocity(params?: object): Promise<any> {
    return this.call("player.set_velocity", params);
  }

  /** Read block id at a single position. */
  async worldBlockAt(params?: object): Promise<any> {
    return this.call("world.block_at", params);
  }

  /** List non-air blocks in a cube around the player. Radius clamped to [0,8]. */
  async worldBlocksAround(params?: object): Promise<any> {
    return this.call("world.blocks_around", params);
  }

  /** Cast a ray from the player's eye in look direction. Returns first hit. */
  async worldRaycast(params?: object): Promise<any> {
    return this.call("world.raycast", params);
  }

  /** Single attackBlock tick at a position. Use world.mine_down or baritone.command "mine" for continuous. */
  async worldMineBlock(params?: object): Promise<any> {
    return this.call("world.mine_block", params);
  }

  /** interactBlock against a target. Auto-aims; replaces water/air directly. Used for placing AND for hoe/seed actions when block id is held. */
  async worldPlaceBlock(params?: object): Promise<any> {
    return this.call("world.place_block", params);
  }

  /** Right-click with held item (eat food, drink potion, throw ender pearl, etc.). */
  async worldUseItem(params?: object): Promise<any> {
    return this.call("world.use_item", params);
  }

  /** Right-click an entity by id (e.g. open a villager trade GUI). */
  async worldInteractEntity(params?: object): Promise<any> {
    return this.call("world.interact_entity", params);
  }

  /** Continuously mine the block under the bot, fall, repeat. Drives updateBlockBreakingProgress from tick callback (the only way to break stone reliably). */
  async worldMineDown(params?: object): Promise<any> {
    return this.call("world.mine_down", params);
  }

  /** Walk-and-place bridge using synthetic BlockHitResult on the floor's edge face. Survives camera-pitch raycast misses. */
  async worldBridge(params?: object): Promise<any> {
    return this.call("world.bridge", params);
  }

  /** Take a single annotated screenshot from the player's POV. Returns base64 PNG/JPEG + entity/block annotations. */
  async worldScreenshot(params?: object): Promise<any> {
    return this.call("world.screenshot", params);
  }

  /** Take N screenshots around the player in a horizontal sweep (yaw step = 360/N). Returns frames[]. */
  async worldScreenshotPanorama(params?: object): Promise<any> {
    return this.call("world.screenshot_panorama", params);
  }

  /** Send a chat line. Strings starting with / route through sendChatCommand. Fire-and-forget: chat-signing path can hang in offline mode. */
  async chatSend(params?: object): Promise<any> {
    return this.call("chat.send", params);
  }

  /** Read the last N chat lines from the bot's seen-messages buffer (cap 256). */
  async chatRecent(params?: object): Promise<any> {
    return this.call("chat.recent", params);
  }

  /** Open the player's own inventory screen (no nearby block needed). Required before SWAP-mode clicks against own inv. */
  async containerOpenInventory(): Promise<any> {
    return this.call("container.open_inventory");
  }

  /** Right-click a block to open it (chest, barrel, crafting table). Caller polls container.state to confirm. */
  async containerOpen(params?: object): Promise<any> {
    return this.call("container.open", params);
  }

  /** Read currently-open screen's slot contents. */
  async containerState(): Promise<any> {
    return this.call("container.state");
  }

  /** Click a slot in the open screen. SlotActionType: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL. */
  async containerClick(params?: object): Promise<any> {
    return this.call("container.click", params);
  }

  /** Close the open screen. */
  async containerClose(): Promise<any> {
    return this.call("container.close");
  }

  /** Pathfind to a goal. Pass {x,y,z} for GoalBlock, {x,z} for GoalXZ, {y} for GoalYLevel. */
  async baritoneGoto(params?: object): Promise<any> {
    return this.call("baritone.goto", params);
  }

  /** Cancel current pathing. */
  async baritoneStop(): Promise<any> {
    return this.call("baritone.stop");
  }

  /** Probe baritone availability and current goal. */
  async baritoneStatus(): Promise<any> {
    return this.call("baritone.status");
  }

  /** Mine N of the given block ids. WARNING: deadlocks the client thread on this build — prefer baritone.command "mine <id> [N]". */
  async baritoneMine(params?: object): Promise<any> {
    return this.call("baritone.mine", params);
  }

  /** Follow an entity by display name. */
  async baritoneFollow(params?: object): Promise<any> {
    return this.call("baritone.follow", params);
  }

  /** Execute a Baritone chat-command (e.g. "mine minecraft:stone 5", "set allowBreak true"). Runs on a dedicated worker — does NOT deadlock the client thread like baritone.mine does. */
  async baritoneCommand(params?: object): Promise<any> {
    return this.call("baritone.command", params);
  }

  /** Pathfind to the nearest block of the given id (e.g. "minecraft:chest"). Bypasses the command parser. */
  async baritoneGetToBlock(params?: object): Promise<any> {
    return this.call("baritone.get_to_block", params);
  }

  /** Walk N blocks along current look direction. No goal-block scan; pure pathfinding test. */
  async baritoneThisway(params?: object): Promise<any> {
    return this.call("baritone.thisway", params);
  }

  /** Set a Baritone setting. Value is auto-parsed from JSON or string form. */
  async baritoneSetting(params?: object): Promise<any> {
    return this.call("baritone.setting", params);
  }

  /** Read a Baritone setting's current value. */
  async baritoneSettingGet(params?: object): Promise<any> {
    return this.call("baritone.setting_get", params);
  }

  /** Schematic build — NOT IMPLEMENTED in v0.1. */
  async baritoneBuild(): Promise<any> {
    return this.call("baritone.build");
  }

  /** List all loaded Meteor module names. */
  async meteorModulesList(): Promise<any> {
    return this.call("meteor.modules.list");
  }

  /** Activate a Meteor module by name (e.g. auto-armor, kill-aura). */
  async meteorModuleEnable(params?: object): Promise<any> {
    return this.call("meteor.module.enable", params);
  }

  /** Deactivate a Meteor module by name. */
  async meteorModuleDisable(params?: object): Promise<any> {
    return this.call("meteor.module.disable", params);
  }

  /** Toggle a Meteor module's active state. */
  async meteorModuleToggle(params?: object): Promise<any> {
    return this.call("meteor.module.toggle", params);
  }

  /** Read whether a module is currently active. */
  async meteorModuleIsActive(params?: object): Promise<any> {
    return this.call("meteor.module.is_active", params);
  }

  /** List the names of all settings on a module. */
  async meteorModuleSettingsList(params?: object): Promise<any> {
    return this.call("meteor.module.settings_list", params);
  }

  /** Read a module setting's current value (string form). */
  async meteorModuleSettingGet(params?: object): Promise<any> {
    return this.call("meteor.module.setting_get", params);
  }

  /** Set a module setting from a string. Bypasses Meteor's chat-command path which fails silently. */
  async meteorModuleSettingSet(params?: object): Promise<any> {
    return this.call("meteor.module.setting_set", params);
  }

}
