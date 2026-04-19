package com.btone.c.handlers;

import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class ContainerHandlers {
    private static final ObjectMapper M = new ObjectMapper();

    private ContainerHandlers() {}

    public static void registerAll(RpcRouter r) {
        // Open the player's own inventory screen — no chest/block needed.
        // Required for SWAP-mode container.click against player main inventory
        // when no nearby container exists (e.g. mid-nether sword-into-hotbar fix).
        // After this returns, container.state shows the player inventory layout:
        //   slot 0: crafting output, 1-4: 2x2 craft grid, 5-8: armor slots,
        //   slot 45: offhand, slots 9-35: main inv, slots 36-44: hotbar.
        // SWAP a main-inv stack into hotbar slot K with: click slot=N, button=K, mode=SWAP.
        r.register("container.open_inventory", params -> ClientThread.call(1_000, () -> {
            var mc = MinecraftClient.getInstance();
            if (mc.player == null) {
                throw new IllegalStateException("no_player");
            }
            mc.setScreen(new InventoryScreen(mc.player));
            ObjectNode n = M.createObjectNode();
            n.put("opened", true);
            return n;
        }));
        r.register("container.open", params -> ClientThread.call(3_000, () -> {
            int x = params.get("x").asInt();
            int y = params.get("y").asInt();
            int z = params.get("z").asInt();
            var mc = MinecraftClient.getInstance();
            var p = mc.player;
            if (p == null || mc.interactionManager == null) {
                throw new IllegalStateException("no_player");
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            // The screen opens asynchronously after the server reply; the caller
            // should poll container.state to confirm.
            ObjectNode n = M.createObjectNode();
            n.put("requested", true);
            return n;
        }));
        r.register("container.state", params -> ClientThread.call(2_000, () -> {
            var mc = MinecraftClient.getInstance();
            ObjectNode n = M.createObjectNode();
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) {
                n.put("open", false);
                return n;
            }
            n.put("open", true);
            n.put("screen", hs.getClass().getSimpleName());
            var handler = hs.getScreenHandler();
            n.put("syncId", handler.syncId);
            var arr = n.putArray("slots");
            for (int i = 0; i < handler.slots.size(); i++) {
                ItemStack s = handler.slots.get(i).getStack();
                if (s.isEmpty()) continue;
                ObjectNode o = arr.addObject();
                o.put("slot", i);
                o.put("id", Registries.ITEM.getId(s.getItem()).toString());
                o.put("count", s.getCount());
            }
            return n;
        }));
        r.register("container.click", params -> ClientThread.call(2_000, () -> {
            int slot = params.get("slot").asInt();
            int button = params.path("button").asInt(0);
            String modeStr = params.path("mode").asText("PICKUP");
            var mc = MinecraftClient.getInstance();
            if (!(mc.currentScreen instanceof HandledScreen<?> hs) || mc.player == null
                    || mc.interactionManager == null) {
                throw new IllegalStateException("no_container");
            }
            SlotActionType mode;
            try { mode = SlotActionType.valueOf(modeStr); }
            catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException("bad_mode:" + modeStr);
            }
            mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slot, button, mode, mc.player);
            ObjectNode n = M.createObjectNode();
            n.put("clicked", true);
            return n;
        }));
        r.register("container.close", params -> ClientThread.call(1_000, () -> {
            var mc = MinecraftClient.getInstance();
            if (mc.currentScreen instanceof HandledScreen<?> && mc.player != null) {
                mc.player.closeHandledScreen();
            }
            ObjectNode n = M.createObjectNode();
            n.put("closed", true);
            return n;
        }));
    }
}
