package com.btone.c.meteor;

import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
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

/**
 * EnsurePickInHotbar — periodic check; when the bot has a pickaxe of at
 * least the configured tier somewhere in the main inventory but no
 * hotbar slot (0-8) holds one, SWAP one onto the currently-held hotbar
 * slot so the next baritone mine action picks it up.
 */
public class EnsurePickInHotbar extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> intervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("interval-ticks")
        .description("Run the check every N ticks (20 ticks = 1 second).")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 200)
        .build());

    private final Setting<String> minTier = sgGeneral.add(new StringSetting.Builder()
        .name("min-tier")
        .description("Minimum pick tier to count as 'have a pick'. One of: wood, stone, iron, diamond, netherite, gold.")
        .defaultValue("stone")
        .build());

    private boolean tickRegistered = false;
    private int tickCounter = 0;

    public EnsurePickInHotbar() {
        super(Categories.Player, "ensure-pick-in-hotbar",
            "Swaps a pickaxe into the hotbar when one exists in main inventory but none is hotbarred.");
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
        // Don't fight the user / agent if a non-player screen is open
        // (chest, crafting). The agent uses container.click directly there.
        if (mc.currentScreen != null) return;

        PlayerInventory inv = p.getInventory();
        int minTierRank = tierRank(minTier.get());

        // Hotbar already has a qualifying pick? Done.
        for (int i = 0; i < 9; i++) {
            if (pickTierRank(inv.getStack(i)) >= minTierRank) return;
        }

        // Find best pick in main inventory (slots 9..35) and offhand if present.
        int bestInvSlot = -1;
        int bestRank = -1;
        for (int i = 9; i < inv.size(); i++) {
            int r = pickTierRank(inv.getStack(i));
            if (r >= minTierRank && r > bestRank) {
                bestRank = r;
                bestInvSlot = i;
            }
        }
        if (bestInvSlot < 0) return; // no pickaxe at the required tier

        // SWAP via the player screen handler (no chest open required).
        // PlayerScreenHandler slot indices: main inv 9-35 maps 1:1 to inv
        // indices 9-35; hotbar 0-8 maps to screen slots 36-44. SWAP mode
        // uses the main-inv source slot index and `button` = hotbar idx.
        int targetHotbarSlot = inv.getSelectedSlot();
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) targetHotbarSlot = 0;

        // Capture the moved item id BEFORE the swap (post-swap, this slot
        // holds whatever was previously in the hotbar destination).
        ItemStack movedStack = inv.getStack(bestInvSlot);
        Identifier movedId = Registries.ITEM.getId(movedStack.getItem());

        try {
            int syncId = p.playerScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, bestInvSlot, targetHotbarSlot,
                SlotActionType.SWAP, p);
        } catch (Throwable t) {
            warning("clickSlot failed: %s", t.getClass().getSimpleName());
            return;
        }

        info("[ensure-pick] swapped pickaxe %s from inv slot %d into hotbar slot %d",
            movedId == null ? "?" : movedId.toString(), bestInvSlot, targetHotbarSlot);
    }

    /** Rank of pick tier (higher = better). -1 if not a pickaxe. */
    private static int pickTierRank(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return -1;
        String path = id.getPath();
        if (!path.endsWith("_pickaxe")) return -1;
        return tierRank(path.substring(0, path.length() - "_pickaxe".length()));
    }

    /**
     * Maps tier name → rank. Accepts the user-facing alias ("wood", "gold")
     * and the registry prefix that actually appears in vanilla item ids
     * ("wooden_pickaxe", "golden_pickaxe"). Unknown → 0 (worse than wood).
     */
    private static int tierRank(String tier) {
        if (tier == null) return 0;
        switch (tier.toLowerCase()) {
            case "wood":
            case "wooden":    return 1;
            case "gold":
            case "golden":    return 2;
            case "stone":     return 3;
            case "iron":      return 4;
            case "diamond":   return 5;
            case "netherite": return 6;
            default:          return 0;
        }
    }
}
