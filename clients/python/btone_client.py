"""
btone-client — JSON-RPC client for btone-mod-c.

GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
Run python3 bin/generate-clients.py to regenerate after mod schema changes.

Usage:
    from btone_client import BtoneClient
    bot = BtoneClient()                      # reads ~/btone-mc-work/config/btone-bridge.json
    state = bot.player_state()
    bot.baritone_goto({"x": 1004, "y": 69, "z": 822})
    # or low-level:
    bot.call("rpc.discover")
"""
from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Optional


DEFAULT_CONFIG = Path.home() / "btone-mc-work" / "config" / "btone-bridge.json"


class BtoneError(RuntimeError):
    """RPC returned ok=false."""

    def __init__(self, code: str, message: str, raw: Mapping[str, Any]):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.raw = raw


class BtoneClient:
    def __init__(
        self,
        host: str = "127.0.0.1",
        port: Optional[int] = None,
        token: Optional[str] = None,
        config_path: Path = DEFAULT_CONFIG,
        timeout: float = 30.0,
    ):
        if port is None or token is None:
            cfg = json.loads(config_path.read_text())
            port = port if port is not None else cfg["port"]
            token = token if token is not None else cfg["token"]
        self._url = f"http://{host}:{port}/rpc"
        self._token = token
        self._timeout = timeout

    def call(self, method: str, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Low-level: POST {method, params} and return .result, or raise BtoneError."""
        body = {"method": method}
        if params is not None:
            body["params"] = params
        req = urllib.request.Request(
            self._url,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self._token}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            payload = json.loads(resp.read())
        if not payload.get("ok"):
            err = payload.get("error", {})
            raise BtoneError(err.get("code", "unknown"), err.get("message", ""), payload)
        return payload.get("result")

    # ---------- typed methods (auto-generated) ----------
    def debug_echo(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Echo whatever params were sent. Useful for transport tests."""
        # Params: params?
        return self.call('debug.echo', params)

    def debug_methods(self) -> Any:
        """List all registered RPC method names."""
        return self.call('debug.methods')

    def rpc_discover(self) -> Any:
        """OpenRPC self-introspection. Returns the full schema of this service."""
        return self.call('rpc.discover')

    def player_state(self) -> Any:
        """Read player position, rotation, health, food, dimension."""
        return self.call('player.state')

    def player_inventory(self) -> Any:
        """Read full main inventory + hotbar selected slot."""
        return self.call('player.inventory')

    def player_equipped(self) -> Any:
        """Read items in main + off hand."""
        return self.call('player.equipped')

    def player_respawn(self) -> Any:
        """Click respawn after a death; closes the death screen."""
        return self.call('player.respawn')

    def player_pillar_up(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Pillar straight up to a target Y by holding jump+use. Block id must be in hotbar."""
        # Params: block?, target_y, max_ticks?
        return self.call('player.pillar_up', params)

    def player_bridge(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Walk-and-place bridge in a horizontal direction (forward+sneak+use loop). DEPRECATED: see world.bridge."""
        # Params: block?, direction, distance, max_ticks?
        return self.call('player.bridge', params)

    def player_stairs_up(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Walk-and-place ascending stairs in a horizontal direction."""
        # Params: block?, direction, distance, max_ticks?
        return self.call('player.stairs_up', params)

    def player_set_rotation(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Force player head + body yaw/pitch. Stomps last* fields so the renderer doesn't interpolate back."""
        # Params: yaw?, pitch?
        return self.call('player.set_rotation', params)

    def player_press_key(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Press or release a vanilla input key (jump/sneak/sprint/attack/use/forward/back/left/right)."""
        # Params: key, action?
        return self.call('player.press_key', params)

    def player_teleport(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Force-set client-side position. Server may snap back on strict anti-cheat. Stuck-pocket escape only."""
        # Params: x, y, z
        return self.call('player.teleport', params)

    def player_set_velocity(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Set player velocity vector (ticks-per-block units). Useful for fall-recovery."""
        # Params: vx?, vy?, vz?
        return self.call('player.set_velocity', params)

    def world_block_at(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Read block id at a single position."""
        # Params: x, y, z
        return self.call('world.block_at', params)

    def world_blocks_around(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """List non-air blocks in a cube around the player. Radius clamped to [0,8]."""
        # Params: radius?
        return self.call('world.blocks_around', params)

    def world_raycast(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Cast a ray from the player's eye in look direction. Returns first hit."""
        # Params: max?
        return self.call('world.raycast', params)

    def world_mine_block(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Single attackBlock tick at a position. Use world.mine_down or baritone.command \"mine\" for continuous."""
        # Params: x, y, z
        return self.call('world.mine_block', params)

    def world_place_block(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """interactBlock against a target. Auto-aims; replaces water/air directly. Used for placing AND for hoe/seed actions when block id is held."""
        # Params: x, y, z, hand?, side?
        return self.call('world.place_block', params)

    def world_use_item(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Right-click with held item (eat food, drink potion, throw ender pearl, etc.)."""
        # Params: hand?
        return self.call('world.use_item', params)

    def world_interact_entity(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Right-click an entity by id (e.g. open a villager trade GUI)."""
        # Params: entityId, hand?
        return self.call('world.interact_entity', params)

    def world_mine_down(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Continuously mine the block under the bot, fall, repeat. Drives updateBlockBreakingProgress from tick callback (the only way to break stone reliably)."""
        # Params: count, max_ticks?
        return self.call('world.mine_down', params)

    def world_bridge(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Walk-and-place bridge using synthetic BlockHitResult on the floor's edge face. Survives camera-pitch raycast misses."""
        # Params: block?, direction, distance, max_ticks?
        return self.call('world.bridge', params)

    def world_screenshot(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Take a single annotated screenshot from the player's POV. Returns base64 PNG/JPEG + entity/block annotations."""
        # Params: width?, yaw?, pitch?, includeHud?, annotateRange?, format?
        return self.call('world.screenshot', params)

    def world_screenshot_panorama(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Take N screenshots around the player in a horizontal sweep (yaw step = 360/N). Returns frames[]."""
        # Params: angles?, width?, yaw?, pitch?, includeHud?, annotateRange?, format?
        return self.call('world.screenshot_panorama', params)

    def chat_send(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Send a chat line. Strings starting with / route through sendChatCommand. Fire-and-forget: chat-signing path can hang in offline mode."""
        # Params: text
        return self.call('chat.send', params)

    def chat_recent(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Read the last N chat lines from the bot's seen-messages buffer (cap 256)."""
        # Params: n?
        return self.call('chat.recent', params)

    def container_open_inventory(self) -> Any:
        """Open the player's own inventory screen (no nearby block needed). Required before SWAP-mode clicks against own inv."""
        return self.call('container.open_inventory')

    def container_open(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Right-click a block to open it (chest, barrel, crafting table). Caller polls container.state to confirm."""
        # Params: x, y, z
        return self.call('container.open', params)

    def container_state(self) -> Any:
        """Read currently-open screen's slot contents."""
        return self.call('container.state')

    def container_click(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Click a slot in the open screen. SlotActionType: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL."""
        # Params: slot, button?, mode?
        return self.call('container.click', params)

    def container_close(self) -> Any:
        """Close the open screen."""
        return self.call('container.close')

    def baritone_goto(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Pathfind to a goal. Pass {x,y,z} for GoalBlock, {x,z} for GoalXZ, {y} for GoalYLevel."""
        # Params: x?, y?, z?
        return self.call('baritone.goto', params)

    def baritone_stop(self) -> Any:
        """Cancel current pathing."""
        return self.call('baritone.stop')

    def baritone_status(self) -> Any:
        """Probe baritone availability and current goal."""
        return self.call('baritone.status')

    def baritone_mine(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Mine N of the given block ids. WARNING: deadlocks the client thread on this build — prefer baritone.command \"mine <id> [N]\"."""
        # Params: blocks, quantity?
        return self.call('baritone.mine', params)

    def baritone_follow(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Follow an entity by display name."""
        # Params: entityName
        return self.call('baritone.follow', params)

    def baritone_command(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Execute a Baritone chat-command (e.g. \"mine minecraft:stone 5\", \"set allowBreak true\"). Runs on a dedicated worker — does NOT deadlock the client thread like baritone.mine does."""
        # Params: text
        return self.call('baritone.command', params)

    def baritone_get_to_block(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Pathfind to the nearest block of the given id (e.g. \"minecraft:chest\"). Bypasses the command parser."""
        # Params: blockId
        return self.call('baritone.get_to_block', params)

    def baritone_thisway(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Walk N blocks along current look direction. No goal-block scan; pure pathfinding test."""
        # Params: distance?
        return self.call('baritone.thisway', params)

    def baritone_setting(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Set a Baritone setting. Value is auto-parsed from JSON or string form."""
        # Params: key, value
        return self.call('baritone.setting', params)

    def baritone_setting_get(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Read a Baritone setting's current value."""
        # Params: key
        return self.call('baritone.setting_get', params)

    def baritone_build(self) -> Any:
        """Schematic build — NOT IMPLEMENTED in v0.1."""
        return self.call('baritone.build')

    def meteor_modules_list(self) -> Any:
        """List all loaded Meteor module names."""
        return self.call('meteor.modules.list')

    def meteor_module_enable(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Activate a Meteor module by name (e.g. auto-armor, kill-aura)."""
        # Params: name
        return self.call('meteor.module.enable', params)

    def meteor_module_disable(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Deactivate a Meteor module by name."""
        # Params: name
        return self.call('meteor.module.disable', params)

    def meteor_module_toggle(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Toggle a Meteor module's active state."""
        # Params: name
        return self.call('meteor.module.toggle', params)

    def meteor_module_is_active(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Read whether a module is currently active."""
        # Params: name
        return self.call('meteor.module.is_active', params)

    def meteor_module_settings_list(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """List the names of all settings on a module."""
        # Params: name
        return self.call('meteor.module.settings_list', params)

    def meteor_module_setting_get(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Read a module setting's current value (string form)."""
        # Params: name, setting
        return self.call('meteor.module.setting_get', params)

    def meteor_module_setting_set(self, params: Optional[Mapping[str, Any]] = None) -> Any:
        """Set a module setting from a string. Bypasses Meteor's chat-command path which fails silently."""
        # Params: name, setting, value
        return self.call('meteor.module.setting_set', params)

