package com.btone.c.meteor;

import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * EnsureFoodInHotbar — when a whitelisted food item exists in main inv
 * but no hotbar slot has any of those foods, SWAP one onto a non-active
 * hotbar slot (default 8, falling back to the first non-tool/non-sword
 * slot). Avoids displacing whatever the bot is currently holding mid-mine.
 */
public class EnsureFoodInHotbar extends Module {
    /** Food whitelist — vanilla 1.21.8 ids the agent prefers for the bot. */
    private static final Set<String> FOOD_WHITELIST = Set.of(
        "minecraft:bread",
        "minecraft:golden_carrot",
        "minecraft:cooked_cod",
        "minecraft:cooked_salmon",
        "minecraft:beetroot",
        "minecraft:melon_slice",
        "minecraft:cooked_porkchop",
        "minecraft:cooked_beef",
        "minecraft:cooked_chicken",
        "minecraft:cooked_mutton",
        "minecraft:cooked_rabbit",
        "minecraft:baked_potato",
        "minecraft:apple",
        "minecraft:pumpkin_pie"
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> intervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interval-ticks")
        .description("Run the check every N ticks (20 ticks = 1 second).")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 200)
        .build());

    private boolean tickRegistered = false;
    private int tickCounter = 0;

    public EnsureFoodInHotbar() {
        super(Categories.Player, "ensure-food-in-hotbar",
            "Swaps a whitelisted food item onto an idle hotbar slot when none of the hotbar holds food.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        ensureTickRegistered();
    }

    private void ensureTickRegistered() {
        if (tickRegistered) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        tickRegistered = true;
    }

    private void tick(MinecraftClient client) {
        if (!isActive()) return;
        if (++tickCounter < intervalTicks.get()) return;
        tickCounter = 0;
        runCheck();
    }

    private void runCheck() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return; // don't fight an open container

        PlayerInventory inv = p.getInventory();

        // Hotbar already has whitelisted food?
        for (int i = 0; i < 9; i++) {
            if (isFood(inv.getStack(i))) return;
        }

        // Find food in main inv (slots 9..35).
        int foodInvSlot = -1;
        for (int i = 9; i < inv.size(); i++) {
            if (isFood(inv.getStack(i))) { foodInvSlot = i; break; }
        }
        if (foodInvSlot < 0) return;

        // Pick a hotbar destination that ISN'T the currently-held slot.
        // Preference: slot 8 if empty, else first non-tool/non-sword slot
        // 0..8 that isn't the held slot.
        int held = inv.getSelectedSlot();
        int target = -1;
        if (held != 8 && inv.getStack(8).isEmpty()) {
            target = 8;
        }
        if (target < 0) {
            for (int i = 0; i < 9; i++) {
                if (i == held) continue;
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) { target = i; break; }
            }
        }
        if (target < 0) {
            for (int i = 0; i < 9; i++) {
                if (i == held) continue;
                if (!isToolOrWeapon(inv.getStack(i))) { target = i; break; }
            }
        }
        if (target < 0) {
            // Every non-held hotbar slot holds a tool/weapon. Don't displace.
            return;
        }

        // Capture food id pre-swap for accurate logging.
        Identifier foodId = Registries.ITEM.getId(inv.getStack(foodInvSlot).getItem());

        try {
            int syncId = p.playerScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, foodInvSlot, target,
                SlotActionType.SWAP, p);
        } catch (Throwable t) {
            warning("clickSlot failed: %s", t.getClass().getSimpleName());
            return;
        }

        info("[ensure-food] swapped %s from inv slot %d into hotbar slot %d",
            foodId == null ? "?" : foodId.toString(), foodInvSlot, target);
    }

    private static boolean isFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return false;
        return FOOD_WHITELIST.contains(id.toString());
    }

    /**
     * True if the stack is a pickaxe / axe / shovel / hoe / sword. Used
     * to avoid displacing a tool when picking a fallback destination slot.
     */
    private static boolean isToolOrWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return path.endsWith("_pickaxe")
            || path.endsWith("_axe")
            || path.endsWith("_shovel")
            || path.endsWith("_hoe")
            || path.endsWith("_sword");
    }
}
