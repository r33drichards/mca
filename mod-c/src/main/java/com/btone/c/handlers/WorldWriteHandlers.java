package com.btone.c.handlers;

import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcHandler;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WorldWriteHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final long TIMEOUT_MS = 2_000;

    private WorldWriteHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("world.mine_block", mineBlock());
        r.register("world.place_block", placeBlock());
        r.register("world.use_item", useItem());
        r.register("world.interact_entity", interactEntity());
        r.register("world.mine_down", mineDown());
        r.register("world.bridge", bridge());
    }

    /**
     * Build a flat horizontal bridge in the given direction. Unlike
     * {@code player.bridge} (which holds forward+use+sneak and relies on
     * the client's raycast to pick a place target), this synthesizes a
     * {@link net.minecraft.util.hit.BlockHitResult} on the chosen face of
     * the current floor block. Placement always targets the correct face
     * regardless of camera pitch, so it works from the edge of a platform
     * where the raycast-based approach silently misses.
     *
     * See {@link BridgeTask} for the tick-loop implementation and the
     * termination conditions.
     *
     * params: { block?: string (default "minecraft:basalt"),
     *           direction: "+x"|"-x"|"+z"|"-z",
     *           distance: int,
     *           max_ticks?: int (default 1200 = 1 min) }
     */
    private static RpcHandler bridge() {
        return params -> {
            String block = params.has("block") ? params.get("block").asText() : "minecraft:basalt";
            String direction = params.get("direction").asText();
            int distance = params.get("distance").asInt();
            int maxTicks = params.has("max_ticks") ? params.get("max_ticks").asInt() : 1200;
            try {
                return BridgeTask.submit(block, direction, distance, maxTicks)
                        .get(120_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                ObjectNode n = M.createObjectNode();
                n.put("ok", false);
                n.put("reason", "rpc_timeout_120s");
                return n;
            }
        };
    }

    /**
     * Break {@code count} blocks straight down from the bot's current feet
     * position. Drives {@link net.minecraft.client.network.ClientPlayerInteractionManager#updateBlockBreakingProgress}
     * from the client-tick callback — the only reliable way to run continuous
     * mining from an external agent (see {@link MineDownTask} for why
     * attackBlock/press_key paths don't work).
     *
     * params: { count: int, max_ticks?: int (default 6000 = ~5 min) }
     * blocks the HTTP thread for up to 5 min waiting for the task to finish.
     */
    private static RpcHandler mineDown() {
        return params -> {
            int count = params.get("count").asInt();
            int maxTicks = params.has("max_ticks") ? params.get("max_ticks").asInt() : 6000;
            try {
                return MineDownTask.submit(count, maxTicks)
                        .get(300_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                ObjectNode n = M.createObjectNode();
                n.put("ok", false);
                n.put("reason", "rpc_timeout_300s");
                return n;
            }
        };
    }

    private static String describeAction(ActionResult ar) {
        // ActionResult is a sealed interface in 1.21+, not an enum. Use the
        // simple class name (Success / Fail / Pass / PassToDefaultBlockAction).
        return ar == null ? "Null" : ar.getClass().getSimpleName();
    }

    private static RpcHandler mineBlock() {
        // Single-tick start; survival mining requires multiple attackBlock calls.
        // For an MVP this primitive is enough — the agent can poll/repeat.
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            int x = params.get("x").asInt();
            int y = params.get("y").asInt();
            int z = params.get("z").asInt();
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) {
                throw new IllegalStateException("no_player");
            }
            BlockPos pos = new BlockPos(x, y, z);
            Direction side = chooseSide(pos);
            aimAt(Vec3d.ofCenter(pos));
            boolean ok = mc.interactionManager.attackBlock(pos, side);
            ObjectNode n = M.createObjectNode();
            n.put("started", ok);
            n.put("side", side.asString());
            return n;
        });
    }

    private static RpcHandler placeBlock() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            int x = params.get("x").asInt();
            int y = params.get("y").asInt();
            int z = params.get("z").asInt();
            String hand = params.path("hand").asText("main");
            // Optional `side` overrides chooseSide() — useful when caller
            // knows which face to click (e.g. UP face of a basalt floor to
            // place a crafting_table on top). Values: up/down/north/south/east/west.
            String sideStr = params.path("side").asText("");
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) {
                throw new IllegalStateException("no_player");
            }
            BlockPos pos = new BlockPos(x, y, z);
            Direction side;
            if (!sideStr.isEmpty()) {
                Direction parsed = null;
                for (Direction d : Direction.values()) {
                    if (d.asString().equalsIgnoreCase(sideStr)) { parsed = d; break; }
                }
                if (parsed == null) throw new IllegalArgumentException("bad_side:" + sideStr);
                side = parsed;
            } else {
                side = chooseSide(pos);
            }
            aimAt(Vec3d.ofCenter(pos));
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);
            ActionResult result = mc.interactionManager.interactBlock(
                    mc.player,
                    "off".equalsIgnoreCase(hand) ? Hand.OFF_HAND : Hand.MAIN_HAND,
                    hit);
            ObjectNode n = M.createObjectNode();
            n.put("result", describeAction(result));
            n.put("side", side.asString());
            return n;
        });
    }

    private static RpcHandler useItem() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            String hand = params == null || params.isNull() ? "main" : params.path("hand").asText("main");
            var mc = MinecraftClient.getInstance();
            if (mc.interactionManager == null || mc.player == null) {
                throw new IllegalStateException("no_player");
            }
            ActionResult result = mc.interactionManager.interactItem(
                    mc.player,
                    "off".equalsIgnoreCase(hand) ? Hand.OFF_HAND : Hand.MAIN_HAND);
            ObjectNode n = M.createObjectNode();
            n.put("result", describeAction(result));
            return n;
        });
    }

    private static RpcHandler interactEntity() {
        return params -> ClientThread.call(TIMEOUT_MS, () -> {
            int id = params.get("entityId").asInt();
            String hand = params.path("hand").asText("main");
            var mc = MinecraftClient.getInstance();
            var p = mc.player;
            if (p == null || mc.world == null || mc.interactionManager == null) {
                throw new IllegalStateException("no_player");
            }
            var e = mc.world.getEntityById(id);
            if (e == null) throw new IllegalArgumentException("no_entity:" + id);
            ActionResult result = mc.interactionManager.interactEntity(
                    p,
                    e,
                    "off".equalsIgnoreCase(hand) ? Hand.OFF_HAND : Hand.MAIN_HAND);
            ObjectNode n = M.createObjectNode();
            n.put("result", describeAction(result));
            return n;
        });
    }

    private static Direction chooseSide(BlockPos pos) {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return Direction.UP;
        Vec3d eye = p.getCameraPosVec(1.0f);
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d dir = center.subtract(eye);
        // Pick the dominant axis of the approach vector and return the inverse face.
        double ax = Math.abs(dir.x), ay = Math.abs(dir.y), az = Math.abs(dir.z);
        if (ax > ay && ax > az) return dir.x > 0 ? Direction.WEST : Direction.EAST;
        if (ay > az) return dir.y > 0 ? Direction.DOWN : Direction.UP;
        return dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private static void aimAt(Vec3d target) {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        Vec3d eye = p.getCameraPosVec(1.0f);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYaw(yaw);
        p.setPitch(pitch);
    }
}
