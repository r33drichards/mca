package com.btone.c.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridge + stairs movement tasks. Same shape as PillarUpTask: a
 * tick-driven state machine on the client thread that holds keys for
 * the duration of the move.
 *
 * BRIDGE_FLAT  — forward + use + sneak. Bot walks forward holding
 *                sneak (won't fall off the front edge) while use places
 *                a block under feet each tick. Builds a flat horizontal
 *                bridge at the bot's starting Y.
 *
 * STAIRS_UP    — forward + use + jump. Bot jumps forward each tick
 *                while use places a block under feet. Climbs in stairs
 *                pattern (one block forward + one block up per step).
 *
 * Both rely on the player's yaw matching the requested travel direction.
 * The task sets yaw + pitch on entry and stomps the last* fields the
 * same way vision does, so the renderer doesn't interpolate the look
 * back to a stale value mid-task.
 */
public final class MovementTasks {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Queue<MovementTasks> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean tickHandlerInstalled = false;

    public enum Mode { BRIDGE_FLAT, STAIRS_UP }

    private final Mode mode;
    private final String blockId;
    private final String direction;     // "+x", "-x", "+z", "-z"
    private final int distance;          // blocks of movement
    private final int maxTicks;
    private final CompletableFuture<ObjectNode> future;
    private double startX = Double.NaN, startZ = Double.NaN;
    private int startY = Integer.MIN_VALUE;
    private int ticksRun = 0;

    private MovementTasks(Mode mode, String blockId, String direction, int distance,
                          int maxTicks, CompletableFuture<ObjectNode> future) {
        this.mode = mode;
        this.blockId = blockId;
        this.direction = direction;
        this.distance = distance;
        this.maxTicks = maxTicks;
        this.future = future;
    }

    public static CompletableFuture<ObjectNode> submit(Mode mode, String blockId,
                                                       String direction, int distance,
                                                       int maxTicks) {
        ensureTickHandler();
        CompletableFuture<ObjectNode> f = new CompletableFuture<>();
        PENDING.add(new MovementTasks(mode, blockId, direction, distance, maxTicks, f));
        return f;
    }

    private static synchronized void ensureTickHandler() {
        if (tickHandlerInstalled) return;
        tickHandlerInstalled = true;
        ClientTickEvents.END_CLIENT_TICK.register(MovementTasks::onTick);
    }

    private static void onTick(MinecraftClient mc) {
        MovementTasks task = PENDING.peek();
        if (task == null) return;
        boolean done;
        try {
            done = task.runOneTick(mc);
        } catch (Throwable t) {
            done = true;
            ObjectNode n = M.createObjectNode();
            n.put("ok", false);
            n.put("reason", "exception");
            n.put("message", t.toString());
            task.future.complete(n);
            releaseAllKeys(mc);
        }
        if (done) PENDING.poll();
    }

    private boolean runOneTick(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            complete(false, "no_world", -1);
            releaseAllKeys(mc);
            return true;
        }
        if (Double.isNaN(startX)) {
            startX = p.getX();
            startZ = p.getZ();
            startY = p.getBlockY();
        }
        ticksRun++;

        // Distance check: how far has the bot moved in the requested direction?
        double moved = directionalDistance(p);
        if (moved >= distance) {
            complete(true, null, p.getBlockY());
            releaseAllKeys(mc);
            return true;
        }
        if (ticksRun >= maxTicks) {
            complete(false, "timeout", p.getBlockY());
            releaseAllKeys(mc);
            return true;
        }

        // Block in hotbar?
        int hotbarSlot = findBlockInHotbar(p, blockId);
        if (hotbarSlot < 0) {
            complete(false, "no_block_in_hotbar", p.getBlockY());
            releaseAllKeys(mc);
            return true;
        }
        if (p.getInventory().getSelectedSlot() != hotbarSlot) {
            p.getInventory().setSelectedSlot(hotbarSlot);
        }

        // Set yaw to face the travel direction. Without this the forward
        // key moves the bot in whatever direction was last selected.
        // MC yaw convention: 0=south(+z), 90=west(-x), 180=north(-z), -90=east(+x).
        float yaw = yawForDirection(direction);
        // Pitch: look down + slightly forward. Bridge wants the use-target
        // to be the front edge of the block under feet — pitch=70 hits that.
        float pitch = 70.0f;
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.setHeadYaw(yaw);
        p.setBodyYaw(yaw);
        p.lastYaw = yaw;
        p.lastPitch = pitch;
        p.lastHeadYaw = yaw;
        p.lastBodyYaw = yaw;
        p.headYaw = yaw;
        p.bodyYaw = yaw;

        // Hold keys per mode
        mc.options.forwardKey.setPressed(true);
        mc.options.useKey.setPressed(true);
        if (mode == Mode.BRIDGE_FLAT) {
            mc.options.sneakKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);
        } else { // STAIRS_UP
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(true);
        }

        return false;
    }

    private double directionalDistance(PlayerEntity p) {
        switch (direction) {
            case "+x": return Math.max(0, p.getX() - startX);
            case "-x": return Math.max(0, startX - p.getX());
            case "+z": return Math.max(0, p.getZ() - startZ);
            case "-z": return Math.max(0, startZ - p.getZ());
            default: return 0;
        }
    }

    private static float yawForDirection(String dir) {
        switch (dir) {
            case "+x": return -90f;   // east
            case "-x": return 90f;    // west
            case "+z": return 0f;     // south
            case "-z": return 180f;   // north
            default:   return 0f;
        }
    }

    private void complete(boolean ok, String reason, int currentY) {
        ObjectNode n = M.createObjectNode();
        n.put("ok", ok);
        if (reason != null) n.put("reason", reason);
        n.put("mode", mode.name());
        n.put("startY", startY);
        n.put("currentY", currentY);
        n.put("ticks", ticksRun);
        n.put("blockId", blockId);
        n.put("direction", direction);
        n.put("distance_requested", distance);
        future.complete(n);
    }

    private static int findBlockInHotbar(PlayerEntity p, String blockId) {
        Identifier targetId = Identifier.tryParse(blockId);
        if (targetId == null) return -1;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(s.getItem());
            if (id.equals(targetId)) return i;
        }
        return -1;
    }

    private static void releaseAllKeys(MinecraftClient mc) {
        try {
            mc.options.forwardKey.setPressed(false);
            mc.options.useKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        } catch (Throwable ignored) {}
    }
}
