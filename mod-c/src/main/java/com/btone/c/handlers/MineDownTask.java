package com.btone.c.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mine-down task — break the block directly under the bot, fall, repeat
 * until N blocks have been broken or maxTicks elapses.
 *
 * Why this exists: {@code world.mine_block} calls {@code attackBlock} which
 * only STARTS a break each call. Vanilla's continuous-break mechanic runs off
 * {@code ClientPlayerInteractionManager.updateBlockBreakingProgress} advanced
 * once per client tick. Spamming attackBlock from an HTTP client restarts
 * the break every call and makes zero progress. Setting attackKey.setPressed
 * also fails: MinecraftClient's input loop uses attackKey.wasPressed() for
 * the initial click, which is event-driven and not triggered by setPressed.
 *
 * This task drives updateBlockBreakingProgress directly from the client-tick
 * callback — same escape hatch PillarUpTask uses for use/jump.
 */
public final class MineDownTask {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Queue<MineDownTask> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean tickHandlerInstalled = false;

    private final int count;
    private final int maxTicks;
    private final CompletableFuture<ObjectNode> future;
    private int startY = Integer.MIN_VALUE;
    private int blocksBroken = 0;
    private int ticksRun = 0;
    private BlockPos currentPos = null;
    private boolean started = false;

    private MineDownTask(int count, int maxTicks, CompletableFuture<ObjectNode> future) {
        this.count = count;
        this.maxTicks = maxTicks;
        this.future = future;
    }

    public static CompletableFuture<ObjectNode> submit(int count, int maxTicks) {
        ensureTickHandler();
        CompletableFuture<ObjectNode> f = new CompletableFuture<>();
        PENDING.add(new MineDownTask(count, maxTicks, f));
        return f;
    }

    private static synchronized void ensureTickHandler() {
        if (tickHandlerInstalled) return;
        tickHandlerInstalled = true;
        ClientTickEvents.END_CLIENT_TICK.register(MineDownTask::onTick);
    }

    private static void onTick(MinecraftClient mc) {
        MineDownTask task = PENDING.peek();
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
            cancelBreak(mc);
        }
        if (done) PENDING.poll();
    }

    private boolean runOneTick(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || mc.world == null || im == null) {
            complete(false, "no_world", -1);
            return true;
        }
        if (startY == Integer.MIN_VALUE) startY = p.getBlockY();
        ticksRun++;

        if (blocksBroken >= count) {
            cancelBreak(mc);
            complete(true, null, p.getBlockY());
            return true;
        }
        if (ticksRun >= maxTicks) {
            cancelBreak(mc);
            complete(false, "timeout", p.getBlockY());
            return true;
        }

        int px = p.getBlockX();
        int py = p.getBlockY();
        int pz = p.getBlockZ();
        BlockPos target = new BlockPos(px, py - 1, pz);

        // If the block below is already air, the bot is falling — wait for it
        // to land. Don't start a break on nothing.
        if (mc.world.getBlockState(target).isAir()) {
            im.cancelBlockBreaking();
            currentPos = null;
            started = false;
            return false;
        }

        // Aim straight down. Pin both pitch and lastPitch: the interpolated
        // renderer pitch is what raytrace uses, and a tween from the prior
        // yaw/pitch means the first tick after submit can target a sideways
        // block and stall.
        p.setPitch(90.0f);
        p.lastPitch = 90.0f;

        // New target → start a break with attackBlock, then continue each
        // subsequent tick with updateBlockBreakingProgress. attackBlock
        // primes the breakingBlock/breakingStage fields that updateBlockBreakingProgress
        // advances; skipping the priming means progress stays at 0.
        if (currentPos == null || !currentPos.equals(target)) {
            currentPos = target;
            started = false;
        }
        if (!started) {
            im.attackBlock(target, Direction.UP);
            started = true;
        } else {
            im.updateBlockBreakingProgress(target, Direction.UP);
        }

        // Check if the block broke this tick. updateBlockBreakingProgress
        // removes the block in-place when progress hits 1.0, so the post-call
        // block state is the authoritative signal.
        if (mc.world.getBlockState(target).isAir()) {
            blocksBroken++;
            currentPos = null;
            started = false;
        }

        return false;
    }

    private void complete(boolean ok, String reason, int reachedY) {
        ObjectNode n = M.createObjectNode();
        n.put("ok", ok);
        if (reason != null) n.put("reason", reason);
        n.put("startY", startY);
        n.put("reachedY", reachedY);
        n.put("blocksBroken", blocksBroken);
        n.put("count", count);
        n.put("ticks", ticksRun);
        future.complete(n);
    }

    private static void cancelBreak(MinecraftClient mc) {
        try {
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        } catch (Throwable ignored) {}
    }
}
