package com.btone.c.handlers;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.SettingsUtil;
import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Baritone-backed pathing/mining/following handlers.
 *
 * Tested against baritone-api-fabric 1.15.0:
 * - {@link IBaritone#getCustomGoalProcess()} → setGoalAndPath / cancel via pathingBehavior
 * - {@link IBaritone#getMineProcess()}#mine(int, Block...) is a default that wraps to
 *   the BlockOptionalMeta variant.
 * - Settings lookup uses the public {@code byLowerName} field on Settings.
 */
public final class BaritoneHandlers {
    private static final ObjectMapper M = new ObjectMapper();

    private BaritoneHandlers() {}

    private static IBaritone primary() {
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null) throw new IllegalStateException("baritone_not_ready");
            return b;
        } catch (LinkageError e) {
            // Catches NoClassDefFoundError, ExceptionInInitializerError, etc.
            throw new IllegalStateException("baritone_not_loaded");
        }
    }

    public static void registerAll(RpcRouter r) {
        r.register("baritone.goto", params -> ClientThread.call(1_000, () -> {
            Goal g = parseGoal(params);
            primary().getCustomGoalProcess().setGoalAndPath(g);
            ObjectNode n = M.createObjectNode();
            n.put("started", true);
            n.put("goal", g.toString());
            return n;
        }));
        r.register("baritone.stop", params -> ClientThread.call(1_000, () -> {
            primary().getPathingBehavior().cancelEverything();
            ObjectNode n = M.createObjectNode();
            n.put("stopped", true);
            return n;
        }));
        r.register("baritone.status", params -> ClientThread.call(1_000, () -> {
            ObjectNode n = M.createObjectNode();
            try {
                IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (b == null) {
                    n.put("hasBaritone", false);
                    n.put("active", false);
                    return n;
                }
                n.put("hasBaritone", true);
                var pb = b.getPathingBehavior();
                n.put("active", pb.isPathing());
                Goal goal = pb.getGoal();
                if (goal == null) goal = b.getCustomGoalProcess().getGoal();
                if (goal != null) n.put("goal", goal.toString());
            } catch (Throwable t) {
                n.put("hasBaritone", false);
                n.put("active", false);
                n.put("error", String.valueOf(t.getMessage()));
            }
            return n;
        }));
        r.register("baritone.mine", params -> ClientThread.call(1_000, () -> {
            int q = params.path("quantity").asInt(-1);
            JsonNode blocksNode = params.get("blocks");
            if (blocksNode == null || !blocksNode.isArray() || blocksNode.isEmpty()) {
                throw new IllegalArgumentException("blocks_required");
            }
            List<Block> blocks = new ArrayList<>();
            for (JsonNode id : blocksNode) {
                Identifier ident = Identifier.tryParse(id.asText());
                if (ident == null) continue;
                Block block = Registries.BLOCK.get(ident);
                if (block == null || block == Blocks.AIR) continue;
                blocks.add(block);
            }
            if (blocks.isEmpty()) throw new IllegalArgumentException("no_valid_blocks");
            primary().getMineProcess().mine(q, blocks.toArray(new Block[0]));
            ObjectNode n = M.createObjectNode();
            n.put("started", true);
            n.put("count", blocks.size());
            return n;
        }));
        r.register("baritone.follow", params -> ClientThread.call(1_000, () -> {
            String name = params.get("entityName").asText();
            primary().getFollowProcess().follow(e -> e.getName().getString().equals(name));
            ObjectNode n = M.createObjectNode();
            n.put("started", true);
            return n;
        }));
        r.register("baritone.build", params -> {
            // Schematic loading is its own rabbit hole — out of scope for v0.1.
            throw new UnsupportedOperationException("baritone.build not implemented in v0.1");
        });
        r.register("baritone.setting", params -> ClientThread.call(1_000, () -> {
            String key = params.get("key").asText();
            JsonNode v = params.get("value");
            if (v == null) throw new IllegalArgumentException("value_required");
            Settings settings = BaritoneAPI.getSettings();
            if (settings == null) throw new IllegalStateException("baritone_settings_unavailable");
            Settings.Setting<?> setting = settings.byLowerName.get(key.toLowerCase());
            if (setting == null) throw new IllegalArgumentException("no_setting:" + key);
            String stringForm = v.isTextual() ? v.asText() : v.toString();
            // SettingsUtil.parseAndApply(Settings, String, String) returns void.
            SettingsUtil.parseAndApply(settings, setting.getName(), stringForm);
            ObjectNode n = M.createObjectNode();
            n.put("applied", true);
            n.put("value", String.valueOf(setting.value));
            return n;
        }));
        r.register("baritone.setting_get", params -> ClientThread.call(1_000, () -> {
            String key = params.get("key").asText();
            Settings settings = BaritoneAPI.getSettings();
            if (settings == null) throw new IllegalStateException("baritone_settings_unavailable");
            Settings.Setting<?> s = settings.byLowerName.get(key.toLowerCase());
            if (s == null) throw new IllegalArgumentException("no_setting:" + key);
            ObjectNode n = M.createObjectNode();
            n.put("key", key);
            n.put("value", String.valueOf(s.value));
            return n;
        }));
    }

    private static Goal parseGoal(JsonNode params) {
        boolean hasX = params.has("x");
        boolean hasY = params.has("y");
        boolean hasZ = params.has("z");
        if (hasX && hasY && hasZ) {
            return new GoalBlock(params.get("x").asInt(), params.get("y").asInt(), params.get("z").asInt());
        }
        if (hasX && hasZ) {
            return new GoalXZ(params.get("x").asInt(), params.get("z").asInt());
        }
        if (hasY) return new GoalYLevel(params.get("y").asInt());
        throw new IllegalArgumentException("need_x_and_z_or_y");
    }
}
