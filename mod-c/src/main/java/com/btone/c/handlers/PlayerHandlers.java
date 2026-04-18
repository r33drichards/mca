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
