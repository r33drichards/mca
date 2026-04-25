package com.btone.c.meteor;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * RunAwayFromDanger — proactive flee module.
 *
 * Two triggers, both individually toggleable in Meteor's GUI:
 *   1. Hostile mob enters {@code range} blocks of the bot.
 *   2. Player HP drops by more than {@code hp-drop-threshold} half-hearts in
 *      one tick (covers fall damage, lava, fire, mobs the scanner missed).
 *
 * Action: stop Baritone, set a new GoalBlock in the direction OPPOSITE the
 * nearest threat (or behind the bot's current facing if HP-only trigger),
 * {@code run-distance} blocks away. Cooldown prevents spamming gotos.
 *
 * Why this exists: nether mining at y≥80 was bottlenecked by recurring
 * fall-deaths to magma cube knockback. Pre-walking to safe spots dodges
 * known dead zones, but only this module REACTS to a threat that emerges
 * mid-cycle. Pairs with no-fall (negates fall damage) and auto-totem
 * (saves the bot if flee fails).
 *
 * Baritone access is reflective so this class stays compilable without a
 * Baritone runtime — same pattern as the rest of mod-c's Baritone glue.
 */
public class RunAwayFromDanger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Trigger if a hostile mob is within this many blocks.")
        .defaultValue(8.0)
        .range(1.0, 32.0)
        .sliderRange(1.0, 32.0)
        .build());

    private final Setting<Double> hpDropThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("hp-drop-threshold")
        .description("Trigger if HP drops more than this many half-hearts in one tick.")
        .defaultValue(2.0)
        .range(0.5, 20.0)
        .sliderRange(0.5, 20.0)
        .build());

    private final Setting<Integer> runDistance = sgGeneral.add(new IntSetting.Builder()
        .name("run-distance")
        .description("How many blocks to flee.")
        .defaultValue(15)
        .range(1, 64)
        .sliderRange(1, 64)
        .build());

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("After triggering, wait this many seconds before triggering again.")
        .defaultValue(5)
        .range(0, 60)
        .sliderRange(0, 60)
        .build());

    private final Setting<Boolean> enableMobTrigger = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-mob-trigger")
        .description("Flee when a hostile mob enters range.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enableHpTrigger = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-hp-trigger")
        .description("Flee when HP drops sharply (covers lava / fire / unseen mobs).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> hostileOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hostile-only")
        .description("Only count HostileEntity instances (zombies, magma cubes, etc — skip animals/villagers).")
        .defaultValue(true)
        .build());

    private float lastHp = 20f;
    private long cooldownUntilMs = 0L;
    private boolean tickRegistered = false;

    public RunAwayFromDanger() {
        super(Categories.Combat, "run-away-from-danger",
            "Pauses Baritone and flees from hostile mobs / sudden HP drops.");
    }

    @Override
    public void onActivate() {
        PlayerEntity p = MinecraftClient.getInstance().player;
        lastHp = p != null ? p.getHealth() : 20f;
        cooldownUntilMs = 0L;
        ensureTickRegistered();
    }

    /**
     * We drive the module from Fabric's tick event instead of Meteor's @EventHandler
     * because Meteor's orbit bus needs a pre-generated LambdaFactory class for
     * each subscriber (normally produced by Meteor's gradle annotation processor,
     * which we don't run). Fabric tick events have no such requirement and fire
     * once per client tick — same cadence as TickEvent.Pre.
     */
    private void ensureTickRegistered() {
        if (tickRegistered) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        tickRegistered = true;
    }

    private void tick(MinecraftClient client) {
        if (!isActive()) return;
        runTick();
    }

    private void runTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        if (now < cooldownUntilMs) {
            lastHp = player.getHealth();
            return;
        }

        float currentHp = player.getHealth();
        boolean hpDropped = enableHpTrigger.get() && (lastHp - currentHp) >= hpDropThreshold.get();
        Vec3d threatPos = null;

        if (enableMobTrigger.get()) {
            double rangeSq = range.get() * range.get();
            double bestSq = Double.POSITIVE_INFINITY;
            for (Entity e : mc.world.getEntities()) {
                if (e == player || !(e instanceof LivingEntity)) continue;
                if (hostileOnly.get() && !(e instanceof HostileEntity)) continue;
                double dSq = e.squaredDistanceTo(player);
                if (dSq <= rangeSq && dSq < bestSq) {
                    bestSq = dSq;
                    threatPos = e.getPos();
                }
            }
        }

        lastHp = currentHp;

        if (!hpDropped && threatPos == null) return;

        // Triggering — set cooldown
        cooldownUntilMs = now + cooldownSeconds.get() * 1000L;

        // Compute flee direction
        Vec3d runDir;
        if (threatPos != null) {
            runDir = player.getPos().subtract(threatPos);
            if (runDir.lengthSquared() < 1e-6) {
                // Threat is exactly on the player (shouldn't happen, but guard)
                runDir = Vec3d.fromPolar(0, player.getYaw() + 180);
            } else {
                runDir = runDir.normalize();
            }
        } else {
            // HP-drop only — flee in the reverse of the bot's facing
            runDir = Vec3d.fromPolar(0, player.getYaw() + 180);
        }
        Vec3d target = player.getPos().add(runDir.multiply(runDistance.get()));
        int gx = (int) Math.round(target.x);
        int gy = (int) Math.round(player.getY());
        int gz = (int) Math.round(target.z);

        try {
            BaritoneFlee.fleeTo(gx, gy, gz);
        } catch (Throwable t) {
            warning("Baritone flee failed: %s", t.getClass().getSimpleName());
            return;
        }
        // Prefix-tagged line first so the external loop's chat.recent grep
        // can detect activation regardless of formatting changes below.
        String reason = threatPos != null ? "mob-in-range" : "hp-drop";
        info("[run-away] triggered: %s", reason);
        info("Fleeing %d blocks to (%d, %d, %d) — threat=%s hp=%.1f",
            runDistance.get(), gx, gy, gz,
            threatPos != null ? "mob" : "hp-drop", currentHp);
    }
}
