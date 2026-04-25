package com.btone.c.meteor;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Surface — when underwater air drops below the threshold and the bot
 * isn't on the ground, hold jump (release sneak) to rise toward the
 * surface; optionally cancels the active Baritone path so it won't keep
 * pulling the bot back down.
 */
public class Surface extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> airThresholdPct = sgGeneral.add(new IntSetting.Builder()
        .name("air-threshold-pct")
        .description("Trigger when remaining air drops below this percent of max air.")
        .defaultValue(50)
        .range(1, 99)
        .sliderRange(1, 99)
        .build());

    private final Setting<Boolean> disableBaritoneWhileRising = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-baritone-while-rising")
        .description("Cancel any active Baritone path while surfacing so it doesn't drag the bot back under.")
        .defaultValue(true)
        .build());

    private boolean tickRegistered = false;
    private boolean rising = false;

    public Surface() {
        super(Categories.Movement, "surface",
            "Holds jump and (optionally) cancels Baritone when the bot is drowning.");
    }

    @Override
    public void onActivate() {
        rising = false;
        ensureTickRegistered();
    }

    @Override
    public void onDeactivate() {
        // Release the jump key if we held it. If the user disables the
        // module mid-rise, leave the world in a clean state.
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (rising) mc.options.jumpKey.setPressed(false);
        } catch (Throwable ignored) {}
        rising = false;
    }

    private void ensureTickRegistered() {
        if (tickRegistered) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        tickRegistered = true;
    }

    private void tick(MinecraftClient client) {
        if (!isActive()) {
            if (rising) {
                try { client.options.jumpKey.setPressed(false); } catch (Throwable ignored) {}
                rising = false;
            }
            return;
        }
        runTick();
    }

    private void runTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            if (rising) { try { mc.options.jumpKey.setPressed(false); } catch (Throwable ignored) {} }
            rising = false;
            return;
        }

        int air = p.getAir();
        int maxAir = p.getMaxAir();
        boolean lowAir = maxAir > 0 && (air * 100) < (maxAir * airThresholdPct.get());

        if (!rising) {
            // Trigger condition: low air, in water, not on ground.
            if (lowAir && p.isSubmergedInWater() && !p.isOnGround()) {
                rising = true;
                // Release sneak (which would keep the bot on the floor) and
                // press jump to start ascending.
                try { mc.options.sneakKey.setPressed(false); } catch (Throwable ignored) {}
                try { mc.options.jumpKey.setPressed(true); } catch (Throwable ignored) {}
                if (disableBaritoneWhileRising.get()) {
                    try { cancelBaritonePath(); } catch (Throwable t) {
                        warning("baritone cancel failed: %s", t.getClass().getSimpleName());
                    }
                }
                info("[surface] triggered: rising — air=%d/%d pos=(%d,%d,%d)",
                    air, maxAir, p.getBlockX(), p.getBlockY(), p.getBlockZ());
            }
            return;
        }

        // Already rising: keep jump held until we surface OR air recovers.
        boolean airAboveThreshold = maxAir > 0 && (air * 100) >= (maxAir * airThresholdPct.get());
        boolean surfaced = p.getY() >= 62.0 || p.isOnGround() || !p.isSubmergedInWater();
        if (airAboveThreshold || surfaced) {
            try { mc.options.jumpKey.setPressed(false); } catch (Throwable ignored) {}
            rising = false;
            info("[surface] released — air=%d/%d y=%.1f surfaced=%s",
                air, maxAir, p.getY(), surfaced);
            return;
        }
        // Re-assert the jump key in case something else released it.
        try { mc.options.jumpKey.setPressed(true); } catch (Throwable ignored) {}
    }

    /**
     * Cancel the current Baritone pathing via reflection. Mirrors
     * BaritoneFlee.fleeTo's reflection style so this class still
     * compiles / loads when Baritone is missing at runtime.
     */
    private static void cancelBaritonePath() throws Exception {
        Class<?> apiCls = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiCls.getMethod("getProvider").invoke(null);
        Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        Object pathing = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
        try {
            pathing.getClass().getMethod("cancelEverything").invoke(pathing);
        } catch (NoSuchMethodException nsme) {
            // Fall back: walk hierarchy
            Class<?> c = pathing.getClass();
            while (c != null) {
                try {
                    var m = c.getDeclaredMethod("cancelEverything");
                    m.setAccessible(true);
                    m.invoke(pathing);
                    return;
                } catch (NoSuchMethodException ignored) {}
                c = c.getSuperclass();
            }
            throw nsme;
        }
    }
}
