package com.btone.c.meteor;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * PanicBoxUp — when the bot's HP drops below a threshold, place blocks
 * around it to seal it inside a 1×1×2 enclosure. Mobs can no longer
 * reach the bot through the cardinal openings, so HP can regenerate
 * (with auto-eat keeping food up) before the bot tries to keep moving.
 *
 * Triggers: {@code health-threshold} (default 6.0 = 3 hearts).
 * Actions per tick while triggered:
 *   1. Find the first placeable block in the hotbar.
 *   2. For each empty cardinal-neighbor block at the bot's feet AND head
 *      level, place one block (4 + 4 = 8 blocks max, usually fewer).
 *   3. Stop placing once the box is sealed; HP regenerates.
 *   4. When HP recovers above {@code release-threshold} (default 16),
 *      stop and let the rest of the routine resume.
 *
 * Doesn't try to mine the box back open — that's the agent's
 * responsibility once HP is up. The bot's ow auto-tool + baritone can
 * resume normal pathing and break out.
 *
 * Why a custom module instead of Meteor's `surround`/`self-trap`:
 * those are tuned for PvP (obsidian against EGapples). This one fits
 * the basic-block constraints of a survival bot (any cobble/basalt
 * works) and ties into the same in-mod machinery as
 * RunAwayFromDanger.
 */
public class PanicBoxUp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> healthThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Activate the panic box when HP drops below this many half-hearts.")
        .defaultValue(6.0)
        .range(1.0, 20.0)
        .sliderRange(1.0, 20.0)
        .build());

    private final Setting<Double> releaseThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("release-threshold")
        .description("Stop placing once HP recovers above this many half-hearts. Box stays in place.")
        .defaultValue(16.0)
        .range(1.0, 20.0)
        .sliderRange(1.0, 20.0)
        .build());

    private final Setting<Integer> ticksBetweenPlaces = sgGeneral.add(new IntSetting.Builder()
        .name("place-cooldown-ticks")
        .description("Ticks to wait between block placements (server rate limit).")
        .defaultValue(4)
        .range(0, 40)
        .sliderRange(0, 40)
        .build());

    private final Setting<Boolean> sealHead = sgGeneral.add(new BoolSetting.Builder()
        .name("seal-head-level")
        .description("Place blocks around the bot's HEAD level too (full 1×1×2 enclosure). Off = feet level only.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sealAbove = sgGeneral.add(new BoolSetting.Builder()
        .name("seal-above")
        .description("Place a block above the head to block ghast/blaze fireballs from above.")
        .defaultValue(true)
        .build());

    private boolean tickRegistered = false;
    private int cooldownLeft = 0;
    private boolean active = false;

    public PanicBoxUp() {
        super(Categories.Combat, "panic-box-up",
            "Surrounds the bot with placeable blocks when HP is critical, then waits for HP to regenerate.");
    }

    @Override
    public void onActivate() {
        cooldownLeft = 0;
        active = false;
        ensureTickRegistered();
    }

    private void ensureTickRegistered() {
        if (tickRegistered) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        tickRegistered = true;
    }

    private void tick(MinecraftClient client) {
        if (!isActive()) {
            active = false;
            return;
        }
        runTick();
    }

    private void runTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        float hp = p.getHealth();
        if (!active && hp >= healthThreshold.get()) return;
        // Trip the active flag once HP drops below threshold; stay
        // active until HP recovers above release-threshold.
        if (!active && hp < healthThreshold.get()) {
            active = true;
            // Emit a chat line so the agent's log monitor sees panic
            // activations the same way it sees flee events.
            info("Boxing up at hp=%.1f (pos %d,%d,%d)",
                hp, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        }
        if (active && hp >= releaseThreshold.get()) {
            info("Released — hp recovered to %.1f", hp);
            active = false;
            return;
        }
        if (cooldownLeft > 0) { cooldownLeft--; return; }

        // Find a placeable block in hotbar
        int hotbarSlot = findPlaceableInHotbar(p);
        if (hotbarSlot < 0) return;
        int prevSlot = p.getInventory().getSelectedSlot();
        if (prevSlot != hotbarSlot) p.getInventory().setSelectedSlot(hotbarSlot);

        BlockPos feet = p.getBlockPos();
        // Place around feet, then head if requested, then above
        if (tryPlaceCardinalsAround(mc, p, feet)) {
            cooldownLeft = ticksBetweenPlaces.get();
            return;
        }
        if (sealHead.get() && tryPlaceCardinalsAround(mc, p, feet.up())) {
            cooldownLeft = ticksBetweenPlaces.get();
            return;
        }
        if (sealAbove.get() && tryPlaceAt(mc, p, feet.up(2))) {
            cooldownLeft = ticksBetweenPlaces.get();
        }
    }

    /** Place blocks at the 4 cardinal neighbors of {@code center} that are currently air. */
    private boolean tryPlaceCardinalsAround(MinecraftClient mc, ClientPlayerEntity p, BlockPos center) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos target = center.offset(d);
            if (mc.world.getBlockState(target).isAir()) {
                if (tryPlaceAt(mc, p, target)) return true;
            }
        }
        return false;
    }

    /**
     * Place a block at {@code target} by interacting against an adjacent
     * solid face. Returns true if the placement attempt was made.
     */
    private boolean tryPlaceAt(MinecraftClient mc, ClientPlayerEntity p, BlockPos target) {
        if (!mc.world.getBlockState(target).isAir()) return false;
        // Find a solid neighbor to click against
        for (Direction d : Direction.values()) {
            BlockPos neighbor = target.offset(d);
            if (mc.world.getBlockState(neighbor).isAir()) continue;
            Direction clickFace = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(clickFace.getVector()).multiply(0.5));
            BlockHitResult hitResult = new BlockHitResult(hit, clickFace, neighbor, false);
            try {
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hitResult);
                p.swingHand(Hand.MAIN_HAND);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static int findPlaceableInHotbar(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BlockItem) return i;
        }
        return -1;
    }
}
