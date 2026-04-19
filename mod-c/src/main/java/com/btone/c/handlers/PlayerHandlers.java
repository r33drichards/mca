package com.btone.c.handlers;

import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

public final class PlayerHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final long TIMEOUT_MS = 2_000;

    private PlayerHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("player.state", state());
        r.register("player.inventory", inventory());
        r.register("player.equipped", equipped());
        r.register("player.respawn", respawn());
        r.register("player.pillar_up", pillarUp());
        r.register("player.bridge", movement(MovementTasks.Mode.BRIDGE_FLAT));
        r.register("player.stairs_up", movement(MovementTasks.Mode.STAIRS_UP));
        r.register("player.set_rotation", setRotation());
        r.register("player.press_key", pressKey());
        r.register("player.teleport", teleport());
        r.register("player.set_velocity", setVelocity());
    }

    private static RpcHandler setVelocity() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            double vx = params.path("vx").asDouble(0);
            double vy = params.path("vy").asDouble(0);
            double vz = params.path("vz").asDouble(0);
            var mc = MinecraftClient.getInstance();
            if (mc.player == null) throw new IllegalStateException("no_player");
            mc.player.setVelocity(vx, vy, vz);
            mc.player.velocityModified = true;
            ObjectNode n = M.createObjectNode();
            n.put("set", true);
            return n;
        });
    }


    /**
     * Force-set the client-side player position. On offline-mode Fabric
     * servers without strict movement validation, the server accepts the
     * resulting position packet and the bot actually teleports. On vanilla
     * online servers with anti-cheat, the server snaps the player back to
     * the last validated position. Use as a stuck-pocket escape when
     * vanilla physics offers no path out (e.g. 1x1 air pocket inside
     * the nether bedrock layer).
     * params: { x, y, z }
     */
    private static RpcHandler teleport() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            double x = params.get("x").asDouble();
            double y = params.get("y").asDouble();
            double z = params.get("z").asDouble();
            var mc = MinecraftClient.getInstance();
            if (mc.player == null) throw new IllegalStateException("no_player");
            mc.player.refreshPositionAndAngles(
                x + 0.5, y, z + 0.5,
                mc.player.getYaw(), mc.player.getPitch()
            );
            mc.player.setVelocity(0, 0, 0);
            ObjectNode n = M.createObjectNode();
            n.put("teleported", true);
            n.put("x", mc.player.getX());
            n.put("y", mc.player.getY());
            n.put("z", mc.player.getZ());
            return n;
        });
    }

    /**
     * Press/hold/release a player input key (jump, sneak, sprint, attack, use,
     * forward, back, left, right). Useful for triggering Meteor's Velocity-mode
     * flight (which requires jump-key held to ascend), or for stuck-pocket
     * escape via held jump + auto-place. params: { key, action: "press"|"release" }
     */
    private static RpcHandler pressKey() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            String key = params.get("key").asText();
            String action = params.path("action").asText("press");
            var mc = MinecraftClient.getInstance();
            if (mc.options == null) throw new IllegalStateException("no_options");
            net.minecraft.client.option.KeyBinding kb = switch (key) {
                case "jump" -> mc.options.jumpKey;
                case "sneak" -> mc.options.sneakKey;
                case "sprint" -> mc.options.sprintKey;
                case "attack" -> mc.options.attackKey;
                case "use" -> mc.options.useKey;
                case "forward" -> mc.options.forwardKey;
                case "back" -> mc.options.backKey;
                case "left" -> mc.options.leftKey;
                case "right" -> mc.options.rightKey;
                default -> throw new IllegalArgumentException("unknown_key:" + key);
            };
            kb.setPressed("press".equalsIgnoreCase(action));
            ObjectNode n = M.createObjectNode();
            n.put("key", key);
            n.put("pressed", kb.isPressed());
            return n;
        });
    }

    /**
     * Bridge / stairs handler factory.
     * params: { block?, direction (+x|-x|+z|-z), distance, max_ticks? }
     */
    private static RpcHandler movement(MovementTasks.Mode mode) {
        return params -> {
            String block = params.has("block") ? params.get("block").asText() : "minecraft:basalt";
            String direction = params.get("direction").asText();
            int distance = params.get("distance").asInt();
            int maxTicks = params.has("max_ticks") ? params.get("max_ticks").asInt() : 400;
            try {
                return MovementTasks.submit(mode, block, direction, distance, maxTicks)
                        .get(60_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                ObjectNode n = M.createObjectNode();
                n.put("ok", false);
                n.put("reason", "rpc_timeout_60s");
                return n;
            }
        };
    }

    /**
     * Set player rotation persistently. Same field-stomping pattern as
     * vision (sets last* / head / body too) so the renderer doesn't
     * interpolate the look back to the saved value.
     * params: { yaw?: float, pitch?: float }
     */
    private static RpcHandler setRotation() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            ObjectNode n = M.createObjectNode();
            if (mc.player == null) { n.put("ok", false); n.put("reason", "no_player"); return n; }
            float yaw = params.has("yaw") ? (float) params.get("yaw").asDouble() : mc.player.getYaw();
            float pitch = params.has("pitch") ? (float) params.get("pitch").asDouble() : mc.player.getPitch();
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            mc.player.setHeadYaw(yaw);
            mc.player.setBodyYaw(yaw);
            mc.player.lastYaw = yaw;
            mc.player.lastPitch = pitch;
            mc.player.lastHeadYaw = yaw;
            mc.player.lastBodyYaw = yaw;
            mc.player.headYaw = yaw;
            mc.player.bodyYaw = yaw;
            n.put("ok", true);
            n.put("yaw", yaw);
            n.put("pitch", pitch);
            return n;
        });
    }

    /**
     * Pillar up to a target Y by holding jump + use while looking down.
     * params: { block?: string (default "minecraft:basalt"),
     *           target_y: int,
     *           max_ticks?: int (default 200 = ~10s) }
     * Blocks the HTTP thread until the tick task finishes (target reached,
     * timeout, or no block in hotbar). The actual stuff runs on the client
     * tick thread; we only wait here.
     */
    private static RpcHandler pillarUp() {
        return params -> {
            String block = params.has("block") ? params.get("block").asText() : "minecraft:basalt";
            int targetY = params.get("target_y").asInt();
            int maxTicks = params.has("max_ticks") ? params.get("max_ticks").asInt() : 200;
            try {
                return PillarUpTask.submit(block, targetY, maxTicks)
                        .get(60_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                ObjectNode n = M.createObjectNode();
                n.put("ok", false);
                n.put("reason", "rpc_timeout_60s");
                return n;
            }
        };
    }

    private static RpcHandler respawn() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            ObjectNode n = M.createObjectNode();
            if (mc.player == null) {
                n.put("respawned", false);
                n.put("reason", "no_player");
                return n;
            }
            mc.player.requestRespawn();
            // Close the DeathScreen if it's up so subsequent screenshots show the world.
            if (mc.currentScreen != null) mc.setScreen(null);
            n.put("respawned", true);
            return n;
        });
    }

    private static RpcHandler state() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            ObjectNode n = M.createObjectNode();
            MinecraftClient mc = MinecraftClient.getInstance();
            var p = mc.player;
            if (p == null || mc.world == null) {
                n.put("inWorld", false);
                return n;
            }
            n.put("inWorld", true);
            ObjectNode pos = n.putObject("pos");
            pos.put("x", p.getX()); pos.put("y", p.getY()); pos.put("z", p.getZ());
            ObjectNode bp = n.putObject("blockPos");
            bp.put("x", p.getBlockX()); bp.put("y", p.getBlockY()); bp.put("z", p.getBlockZ());
            ObjectNode rot = n.putObject("rot");
            rot.put("yaw", p.getYaw()); rot.put("pitch", p.getPitch());
            n.put("health", p.getHealth());
            n.put("food", p.getHungerManager().getFoodLevel());
            n.put("dim", mc.world.getRegistryKey().getValue().toString());
            n.put("name", p.getName().getString());
            return n;
        });
    }

    private static RpcHandler inventory() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            ObjectNode n = M.createObjectNode();
            var p = MinecraftClient.getInstance().player;
            if (p == null) { n.put("inWorld", false); return n; }
            PlayerInventory inv = p.getInventory();
            n.put("inWorld", true);
            n.put("hotbarSlot", inv.getSelectedSlot());
            var arr = n.putArray("main");
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                ObjectNode o = arr.addObject();
                o.put("slot", i);
                o.put("id", Registries.ITEM.getId(s.getItem()).toString());
                o.put("count", s.getCount());
            }
            return n;
        });
    }

    private static RpcHandler equipped() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            ObjectNode n = M.createObjectNode();
            var p = MinecraftClient.getInstance().player;
            if (p == null) { n.put("inWorld", false); return n; }
            ItemStack main = p.getMainHandStack();
            ItemStack off = p.getOffHandStack();
            ObjectNode mainNode = n.putObject("mainHand");
            mainNode.put("id", Registries.ITEM.getId(main.getItem()).toString());
            mainNode.put("count", main.getCount());
            mainNode.put("empty", main.isEmpty());
            ObjectNode offNode = n.putObject("offHand");
            offNode.put("id", Registries.ITEM.getId(off.getItem()).toString());
            offNode.put("count", off.getCount());
            offNode.put("empty", off.isEmpty());
            return n;
        });
    }
}
