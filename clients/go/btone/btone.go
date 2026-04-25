// btone — JSON-RPC client for btone-mod-c.
//
// GENERATED FROM proto/btone-openrpc.json — DO NOT EDIT BY HAND.
// Run python3 bin/generate-clients.py to regenerate after mod schema changes.
package btone

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

type Config struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
}

func DefaultConfigPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "btone-mc-work", "config", "btone-bridge.json")
}

func LoadConfig(path string) (Config, error) {
	var c Config
	if path == "" {
		path = DefaultConfigPath()
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return c, fmt.Errorf("read btone config %s: %w", path, err)
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("parse btone config: %w", err)
	}
	return c, nil
}

type Client struct {
	url     string
	token   string
	http    *http.Client
}

func New() (*Client, error) {
	return NewWithConfig("")
}

func NewWithConfig(configPath string) (*Client, error) {
	cfg, err := LoadConfig(configPath)
	if err != nil {
		return nil, err
	}
	return NewClient("127.0.0.1", cfg.Port, cfg.Token), nil
}

func NewClient(host string, port int, token string) *Client {
	return &Client{
		url:   fmt.Sprintf("http://%s:%d/rpc", host, port),
		token: token,
		http:  &http.Client{Timeout: 30 * time.Second},
	}
}

type rpcEnvelope struct {
	Ok     bool             `json:"ok"`
	Result json.RawMessage  `json:"result,omitempty"`
	Error  *RpcError        `json:"error,omitempty"`
}

type RpcError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *RpcError) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

// Call is the low-level entry point. Marshal params, POST, return raw result bytes.
// Use the typed methods below for everyday work.
func (c *Client) Call(method string, params any) (json.RawMessage, error) {
	req := map[string]any{"method": method}
	if params != nil {
		req["params"] = params
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	httpReq, err := http.NewRequest("POST", c.url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+c.token)
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var env rpcEnvelope
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	if !env.Ok {
		if env.Error == nil {
			return nil, fmt.Errorf("rpc failed without error body")
		}
		return nil, env.Error
	}
	return env.Result, nil
}

// CallTyped is a convenience wrapper that decodes result into target.
func (c *Client) CallTyped(method string, params any, target any) error {
	raw, err := c.Call(method, params)
	if err != nil {
		return err
	}
	if target == nil {
		return nil
	}
	return json.Unmarshal(raw, target)
}

// ---------- typed methods (auto-generated) ----------
// Echo whatever params were sent. Useful for transport tests.
func (c *Client) DebugEcho(params map[string]any) (json.RawMessage, error) {
	return c.Call("debug.echo", params)
}

// List all registered RPC method names.
func (c *Client) DebugMethods() (json.RawMessage, error) {
	return c.Call("debug.methods", nil)
}

// OpenRPC self-introspection. Returns the full schema of this service.
func (c *Client) RpcDiscover() (json.RawMessage, error) {
	return c.Call("rpc.discover", nil)
}

// Read player position, rotation, health, food, dimension.
func (c *Client) PlayerState() (json.RawMessage, error) {
	return c.Call("player.state", nil)
}

// Read full main inventory + hotbar selected slot.
func (c *Client) PlayerInventory() (json.RawMessage, error) {
	return c.Call("player.inventory", nil)
}

// Read items in main + off hand.
func (c *Client) PlayerEquipped() (json.RawMessage, error) {
	return c.Call("player.equipped", nil)
}

// Click respawn after a death; closes the death screen.
func (c *Client) PlayerRespawn() (json.RawMessage, error) {
	return c.Call("player.respawn", nil)
}

// Pillar straight up to a target Y by holding jump+use. Block id must be in hotbar.
func (c *Client) PlayerPillarUp(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.pillar_up", params)
}

// Walk-and-place bridge in a horizontal direction (forward+sneak+use loop). DEPRECATED: see world.bridge.
func (c *Client) PlayerBridge(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.bridge", params)
}

// Walk-and-place ascending stairs in a horizontal direction.
func (c *Client) PlayerStairsUp(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.stairs_up", params)
}

// Force player head + body yaw/pitch. Stomps last* fields so the renderer doesn't interpolate back.
func (c *Client) PlayerSetRotation(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.set_rotation", params)
}

// Press or release a vanilla input key (jump/sneak/sprint/attack/use/forward/back/left/right).
func (c *Client) PlayerPressKey(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.press_key", params)
}

// Force-set client-side position. Server may snap back on strict anti-cheat. Stuck-pocket escape only.
func (c *Client) PlayerTeleport(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.teleport", params)
}

// Set player velocity vector (ticks-per-block units). Useful for fall-recovery.
func (c *Client) PlayerSetVelocity(params map[string]any) (json.RawMessage, error) {
	return c.Call("player.set_velocity", params)
}

// Read block id at a single position.
func (c *Client) WorldBlockAt(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.block_at", params)
}

// List non-air blocks in a cube around the player. Radius clamped to [0,8].
func (c *Client) WorldBlocksAround(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.blocks_around", params)
}

// Cast a ray from the player's eye in look direction. Returns first hit.
func (c *Client) WorldRaycast(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.raycast", params)
}

// Single attackBlock tick at a position. Use world.mine_down or baritone.command "mine" for continuous.
func (c *Client) WorldMineBlock(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.mine_block", params)
}

// interactBlock against a target. Auto-aims; replaces water/air directly. Used for placing AND for hoe/seed actions when block id is held.
func (c *Client) WorldPlaceBlock(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.place_block", params)
}

// Right-click with held item (eat food, drink potion, throw ender pearl, etc.).
func (c *Client) WorldUseItem(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.use_item", params)
}

// Right-click an entity by id (e.g. open a villager trade GUI).
func (c *Client) WorldInteractEntity(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.interact_entity", params)
}

// Continuously mine the block under the bot, fall, repeat. Drives updateBlockBreakingProgress from tick callback (the only way to break stone reliably).
func (c *Client) WorldMineDown(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.mine_down", params)
}

// Walk-and-place bridge using synthetic BlockHitResult on the floor's edge face. Survives camera-pitch raycast misses.
func (c *Client) WorldBridge(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.bridge", params)
}

// Take a single annotated screenshot from the player's POV. Returns base64 PNG/JPEG + entity/block annotations.
func (c *Client) WorldScreenshot(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.screenshot", params)
}

// Take N screenshots around the player in a horizontal sweep (yaw step = 360/N). Returns frames[].
func (c *Client) WorldScreenshotPanorama(params map[string]any) (json.RawMessage, error) {
	return c.Call("world.screenshot_panorama", params)
}

// Send a chat line. Strings starting with / route through sendChatCommand. Fire-and-forget: chat-signing path can hang in offline mode.
func (c *Client) ChatSend(params map[string]any) (json.RawMessage, error) {
	return c.Call("chat.send", params)
}

// Read the last N chat lines from the bot's seen-messages buffer (cap 256).
func (c *Client) ChatRecent(params map[string]any) (json.RawMessage, error) {
	return c.Call("chat.recent", params)
}

// Open the player's own inventory screen (no nearby block needed). Required before SWAP-mode clicks against own inv.
func (c *Client) ContainerOpenInventory() (json.RawMessage, error) {
	return c.Call("container.open_inventory", nil)
}

// Right-click a block to open it (chest, barrel, crafting table). Caller polls container.state to confirm.
func (c *Client) ContainerOpen(params map[string]any) (json.RawMessage, error) {
	return c.Call("container.open", params)
}

// Read currently-open screen's slot contents.
func (c *Client) ContainerState() (json.RawMessage, error) {
	return c.Call("container.state", nil)
}

// Click a slot in the open screen. SlotActionType: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL.
func (c *Client) ContainerClick(params map[string]any) (json.RawMessage, error) {
	return c.Call("container.click", params)
}

// Close the open screen.
func (c *Client) ContainerClose() (json.RawMessage, error) {
	return c.Call("container.close", nil)
}

// Pathfind to a goal. Pass {x,y,z} for GoalBlock, {x,z} for GoalXZ, {y} for GoalYLevel.
func (c *Client) BaritoneGoto(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.goto", params)
}

// Cancel current pathing.
func (c *Client) BaritoneStop() (json.RawMessage, error) {
	return c.Call("baritone.stop", nil)
}

// Probe baritone availability and current goal.
func (c *Client) BaritoneStatus() (json.RawMessage, error) {
	return c.Call("baritone.status", nil)
}

// Mine N of the given block ids. WARNING: deadlocks the client thread on this build — prefer baritone.command "mine <id> [N]".
func (c *Client) BaritoneMine(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.mine", params)
}

// Follow an entity by display name.
func (c *Client) BaritoneFollow(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.follow", params)
}

// Execute a Baritone chat-command (e.g. "mine minecraft:stone 5", "set allowBreak true"). Runs on a dedicated worker — does NOT deadlock the client thread like baritone.mine does.
func (c *Client) BaritoneCommand(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.command", params)
}

// Pathfind to the nearest block of the given id (e.g. "minecraft:chest"). Bypasses the command parser.
func (c *Client) BaritoneGetToBlock(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.get_to_block", params)
}

// Walk N blocks along current look direction. No goal-block scan; pure pathfinding test.
func (c *Client) BaritoneThisway(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.thisway", params)
}

// Set a Baritone setting. Value is auto-parsed from JSON or string form.
func (c *Client) BaritoneSetting(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.setting", params)
}

// Read a Baritone setting's current value.
func (c *Client) BaritoneSettingGet(params map[string]any) (json.RawMessage, error) {
	return c.Call("baritone.setting_get", params)
}

// Schematic build — NOT IMPLEMENTED in v0.1.
func (c *Client) BaritoneBuild() (json.RawMessage, error) {
	return c.Call("baritone.build", nil)
}

// List all loaded Meteor module names.
func (c *Client) MeteorModulesList() (json.RawMessage, error) {
	return c.Call("meteor.modules.list", nil)
}

// Activate a Meteor module by name (e.g. auto-armor, kill-aura).
func (c *Client) MeteorModuleEnable(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.enable", params)
}

// Deactivate a Meteor module by name.
func (c *Client) MeteorModuleDisable(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.disable", params)
}

// Toggle a Meteor module's active state.
func (c *Client) MeteorModuleToggle(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.toggle", params)
}

// Read whether a module is currently active.
func (c *Client) MeteorModuleIsActive(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.is_active", params)
}

// List the names of all settings on a module.
func (c *Client) MeteorModuleSettingsList(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.settings_list", params)
}

// Read a module setting's current value (string form).
func (c *Client) MeteorModuleSettingGet(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.setting_get", params)
}

// Set a module setting from a string. Bypasses Meteor's chat-command path which fails silently.
func (c *Client) MeteorModuleSettingSet(params map[string]any) (json.RawMessage, error) {
	return c.Call("meteor.module.setting_set", params)
}

