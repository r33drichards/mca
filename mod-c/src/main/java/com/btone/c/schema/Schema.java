package com.btone.c.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Single source of truth for the btone JSON-RPC surface.
 *
 * Emits an OpenRPC 1.2.6-shaped document describing every method registered
 * by {@code BtoneC.onInitializeClient}. Two consumers:
 *
 *   1) {@code SpecMain.main} writes {@code proto/btone-openrpc.json} at
 *      build time — this drives client-codegen for Go/Python/TS and the CLI.
 *   2) {@code rpc.discover} (registered in {@code BtoneC}) returns
 *      {@link #buildOpenRpc()} to the caller at runtime.
 *
 * The two stay in sync because the spec is the same Java code in both paths.
 *
 * Types (e.g. PlayerState, Vec3d) live in {@code components/schemas} and are
 * referenced by {@code $ref}. Param-bag types (e.g. Teleport_params) are
 * defined per-method to mirror the JSON object the handler reads.
 */
public final class Schema {
    private static final ObjectMapper M = new ObjectMapper();

    private Schema() {}

    /** OpenRPC 1.2.6 document covering every registered btone method. */
    public static ObjectNode buildOpenRpc() {
        ObjectNode root = M.createObjectNode();
        root.put("openrpc", "1.2.6");

        ObjectNode info = root.putObject("info");
        info.put("title", "btone");
        info.put("version", "0.1.0");
        info.put("description",
                "JSON-RPC over HTTP for the btone-mod-c Minecraft Fabric mod. "
                        + "POST {method, params} to /rpc with Authorization: Bearer <token>.");

        ArrayNode servers = root.putArray("servers");
        ObjectNode srv = servers.addObject();
        srv.put("name", "local-bridge");
        srv.put("url", "http://127.0.0.1:25591/rpc");
        srv.put("summary",
                "Local HTTP bridge. Port + bearer token written to "
                        + "~/btone-mc-work/config/btone-bridge.json on mod start.");

        ArrayNode methods = root.putArray("methods");
        ObjectNode components = root.putObject("components");
        ObjectNode schemas = components.putObject("schemas");

        defineSharedTypes(schemas);
        defineMethods(methods, schemas);

        return root;
    }

    // ===== shared component schemas =====

    private static void defineSharedTypes(ObjectNode schemas) {
        // Vec3d — float position
        type(schemas, "Vec3d",
                "3D position (floats).",
                "x", "number", true,
                "y", "number", true,
                "z", "number", true);

        // Vec3i — integer block position
        type(schemas, "Vec3i",
                "Integer block coordinates.",
                "x", "integer", true,
                "y", "integer", true,
                "z", "integer", true);

        // Rotation
        type(schemas, "Rotation",
                "Player camera angles.",
                "yaw", "number", true,
                "pitch", "number", true);

        // Block — id + air flag
        type(schemas, "Block",
                "Block at a position.",
                "id", "string", true,
                "air", "boolean", true);

        // BlockAt — Block + coords
        type(schemas, "BlockWithPos",
                "Block id with explicit position.",
                "x", "integer", true,
                "y", "integer", true,
                "z", "integer", true,
                "id", "string", true);

        // ItemStack
        type(schemas, "ItemStack",
                "Inventory item entry.",
                "slot", "integer", false,
                "id", "string", true,
                "count", "integer", true,
                "empty", "boolean", false);

        // PlayerState
        ObjectNode ps = schemas.putObject("PlayerState");
        ps.put("type", "object");
        ps.put("description", "Player position, health, food, and dimension.");
        ObjectNode psp = ps.putObject("properties");
        psp.set("inWorld", primitive("boolean"));
        psp.set("pos", ref("Vec3d"));
        psp.set("blockPos", ref("Vec3i"));
        psp.set("rot", ref("Rotation"));
        psp.set("health", primitive("number"));
        psp.set("food", primitive("integer"));
        psp.set("dim", primitive("string"));
        psp.set("name", primitive("string"));

        // Inventory
        ObjectNode inv = schemas.putObject("Inventory");
        inv.put("type", "object");
        ObjectNode invp = inv.putObject("properties");
        invp.set("inWorld", primitive("boolean"));
        invp.set("hotbarSlot", primitive("integer"));
        ObjectNode invMain = invp.putObject("main");
        invMain.put("type", "array");
        invMain.set("items", ref("ItemStack"));

        // EquippedHands
        ObjectNode eq = schemas.putObject("EquippedHands");
        eq.put("type", "object");
        ObjectNode eqp = eq.putObject("properties");
        eqp.set("inWorld", primitive("boolean"));
        eqp.set("mainHand", ref("ItemStack"));
        eqp.set("offHand", ref("ItemStack"));

        // RaycastResult
        ObjectNode rc = schemas.putObject("RaycastResult");
        rc.put("type", "object");
        rc.put("description", "First entity or block intersected by a ray from the player's eye.");
        ObjectNode rcp = rc.putObject("properties");
        rcp.set("type", primitive("string", "MISS|BLOCK|ENTITY"));
        rcp.set("x", primitive("integer"));
        rcp.set("y", primitive("integer"));
        rcp.set("z", primitive("integer"));
        rcp.set("side", primitive("string"));
        rcp.set("id", primitive("string"));
        rcp.set("entityId", primitive("integer"));
        rcp.set("entityType", primitive("string"));
        rcp.set("entityName", primitive("string"));

        // ScreenshotFrame
        ObjectNode sf = schemas.putObject("ScreenshotFrame");
        sf.put("type", "object");
        ObjectNode sfp = sf.putObject("properties");
        sfp.set("image", primitive("string", "base64-encoded PNG/JPEG"));
        sfp.set("format", primitive("string"));
        sfp.set("width", primitive("integer"));
        sfp.set("height", primitive("integer"));
        sfp.set("yaw", primitive("number"));
        sfp.set("pitch", primitive("number"));
        sfp.set("camera", ref("Vec3d"));
        ObjectNode anns = sfp.putObject("annotations");
        anns.put("type", "object");

        // PanoramaResult
        ObjectNode pr = schemas.putObject("PanoramaResult");
        pr.put("type", "object");
        ObjectNode prp = pr.putObject("properties");
        ObjectNode frames = prp.putObject("frames");
        frames.put("type", "array");
        frames.set("items", ref("ScreenshotFrame"));

        // ChatMessages
        ObjectNode cm = schemas.putObject("ChatMessages");
        cm.put("type", "object");
        ObjectNode cmp = cm.putObject("properties");
        ObjectNode msgs = cmp.putObject("messages");
        msgs.put("type", "array");
        msgs.set("items", primitive("string"));

        // ContainerSlots
        ObjectNode cs = schemas.putObject("ContainerState");
        cs.put("type", "object");
        ObjectNode csp = cs.putObject("properties");
        csp.set("open", primitive("boolean"));
        csp.set("screen", primitive("string"));
        csp.set("syncId", primitive("integer"));
        ObjectNode slots = csp.putObject("slots");
        slots.put("type", "array");
        slots.set("items", ref("ItemStack"));

        // BlocksAround
        ObjectNode ba = schemas.putObject("BlocksAround");
        ba.put("type", "object");
        ObjectNode bap = ba.putObject("properties");
        ObjectNode bblocks = bap.putObject("blocks");
        bblocks.put("type", "array");
        bblocks.set("items", ref("BlockWithPos"));

        // Generic ack-style result
        type(schemas, "OkAck",
                "Generic boolean acknowledgement.",
                "ok", "boolean", true,
                "reason", "string", false);

        // Free-form object (e.g. setting list from meteor)
        ObjectNode fo = schemas.putObject("AnyObject");
        fo.put("type", "object");
        fo.put("additionalProperties", true);
    }

    // ===== methods =====

    private static void defineMethods(ArrayNode methods, ObjectNode schemas) {
        // ----- debug -----
        method(methods, schemas, "debug.echo",
                "Echo whatever params were sent. Useful for transport tests.",
                params("AnyObject"),
                resRef("AnyObject"));

        method(methods, schemas, "debug.methods",
                "List all registered RPC method names.",
                params(),
                resInline(obj("methods", arr("string"))));

        method(methods, schemas, "rpc.discover",
                "OpenRPC self-introspection. Returns the full schema of this service.",
                params(),
                resRef("AnyObject"));

        // ----- player -----
        method(methods, schemas, "player.state",
                "Read player position, rotation, health, food, dimension.",
                params(),
                resRef("PlayerState"));

        method(methods, schemas, "player.inventory",
                "Read full main inventory + hotbar selected slot.",
                params(),
                resRef("Inventory"));

        method(methods, schemas, "player.equipped",
                "Read items in main + off hand.",
                params(),
                resRef("EquippedHands"));

        method(methods, schemas, "player.respawn",
                "Click respawn after a death; closes the death screen.",
                params(),
                resInline(obj("respawned", primitive("boolean"), "reason", primitive("string"))));

        method(methods, schemas, "player.pillar_up",
                "Pillar straight up to a target Y by holding jump+use. Block id must be in hotbar.",
                params(
                        param("block", "string", false, "Block id (default minecraft:basalt)."),
                        param("target_y", "integer", true, "Target Y level."),
                        param("max_ticks", "integer", false, "Hard cap (default 200 ≈ 10s).")),
                resRef("OkAck"));

        method(methods, schemas, "player.bridge",
                "Walk-and-place bridge in a horizontal direction (forward+sneak+use loop). DEPRECATED: see world.bridge.",
                params(
                        param("block", "string", false, "Block id in hotbar (default basalt)."),
                        param("direction", "string", true, "+x | -x | +z | -z"),
                        param("distance", "integer", true, "Number of blocks to place."),
                        param("max_ticks", "integer", false, "Hard cap (default 400).")),
                resRef("OkAck"));

        method(methods, schemas, "player.stairs_up",
                "Walk-and-place ascending stairs in a horizontal direction.",
                params(
                        param("block", "string", false, "Block id in hotbar."),
                        param("direction", "string", true, "+x | -x | +z | -z"),
                        param("distance", "integer", true, "Stair steps."),
                        param("max_ticks", "integer", false, "Hard cap (default 400).")),
                resRef("OkAck"));

        method(methods, schemas, "player.set_rotation",
                "Force player head + body yaw/pitch. Stomps last* fields so the renderer doesn't interpolate back.",
                params(
                        param("yaw", "number", false, "Degrees (north=180, south=0)."),
                        param("pitch", "number", false, "-90 (up) to 90 (down).")),
                resInline(obj("ok", primitive("boolean"), "yaw", primitive("number"), "pitch", primitive("number"))));

        method(methods, schemas, "player.press_key",
                "Press or release a vanilla input key (jump/sneak/sprint/attack/use/forward/back/left/right).",
                params(
                        param("key", "string", true, "jump|sneak|sprint|attack|use|forward|back|left|right"),
                        param("action", "string", false, "press (default) or release")),
                resInline(obj("key", primitive("string"), "pressed", primitive("boolean"))));

        method(methods, schemas, "player.teleport",
                "Force-set client-side position. Server may snap back on strict anti-cheat. Stuck-pocket escape only.",
                params(
                        param("x", "number", true, null),
                        param("y", "number", true, null),
                        param("z", "number", true, null)),
                resInline(obj(
                        "teleported", primitive("boolean"),
                        "x", primitive("number"),
                        "y", primitive("number"),
                        "z", primitive("number"))));

        method(methods, schemas, "player.set_velocity",
                "Set player velocity vector (ticks-per-block units). Useful for fall-recovery.",
                params(
                        param("vx", "number", false, null),
                        param("vy", "number", false, null),
                        param("vz", "number", false, null)),
                resInline(obj("set", primitive("boolean"))));

        // ----- world (read) -----
        method(methods, schemas, "world.block_at",
                "Read block id at a single position.",
                params(
                        param("x", "integer", true, null),
                        param("y", "integer", true, null),
                        param("z", "integer", true, null)),
                resRef("Block"));

        method(methods, schemas, "world.blocks_around",
                "List non-air blocks in a cube around the player. Radius clamped to [0,8].",
                params(
                        param("radius", "integer", false, "0..8 (default 3)")),
                resRef("BlocksAround"));

        method(methods, schemas, "world.raycast",
                "Cast a ray from the player's eye in look direction. Returns first hit.",
                params(
                        param("max", "number", false, "Max distance (default 5.0)")),
                resRef("RaycastResult"));

        // ----- world (write) -----
        method(methods, schemas, "world.mine_block",
                "Single attackBlock tick at a position. Use world.mine_down or baritone.command \"mine\" for continuous.",
                params(
                        param("x", "integer", true, null),
                        param("y", "integer", true, null),
                        param("z", "integer", true, null)),
                resInline(obj("started", primitive("boolean"), "side", primitive("string"))));

        method(methods, schemas, "world.place_block",
                "interactBlock against a target. Auto-aims; replaces water/air directly. Used for placing AND for hoe/seed actions when block id is held.",
                params(
                        param("x", "integer", true, null),
                        param("y", "integer", true, null),
                        param("z", "integer", true, null),
                        param("hand", "string", false, "main (default) | off"),
                        param("side", "string", false, "up|down|north|south|east|west — overrides auto-chooseSide")),
                resInline(obj("result", primitive("string"), "side", primitive("string"))));

        method(methods, schemas, "world.use_item",
                "Right-click with held item (eat food, drink potion, throw ender pearl, etc.).",
                params(
                        param("hand", "string", false, "main (default) | off")),
                resInline(obj("result", primitive("string"))));

        method(methods, schemas, "world.interact_entity",
                "Right-click an entity by id (e.g. open a villager trade GUI).",
                params(
                        param("entityId", "integer", true, "Numeric entity id from raycast/blocks_around."),
                        param("hand", "string", false, "main (default) | off")),
                resInline(obj("result", primitive("string"))));

        method(methods, schemas, "world.mine_down",
                "Continuously mine the block under the bot, fall, repeat. Drives updateBlockBreakingProgress from tick callback (the only way to break stone reliably).",
                params(
                        param("count", "integer", true, "Number of blocks to break."),
                        param("max_ticks", "integer", false, "Hard cap (default 6000 ≈ 5min).")),
                resRef("OkAck"));

        method(methods, schemas, "world.bridge",
                "Walk-and-place bridge using synthetic BlockHitResult on the floor's edge face. Survives camera-pitch raycast misses.",
                params(
                        param("block", "string", false, "Block id in hotbar (default basalt)."),
                        param("direction", "string", true, "+x | -x | +z | -z"),
                        param("distance", "integer", true, "Number of blocks to place."),
                        param("max_ticks", "integer", false, "Hard cap (default 1200 ≈ 1min).")),
                resRef("OkAck"));

        // ----- world (vision) -----
        method(methods, schemas, "world.screenshot",
                "Take a single annotated screenshot from the player's POV. Returns base64 PNG/JPEG + entity/block annotations.",
                params(
                        param("width", "integer", false, "Desired width; height computed from FB ratio."),
                        param("yaw", "number", false, "Override yaw for this shot."),
                        param("pitch", "number", false, "Override pitch."),
                        param("includeHud", "boolean", false, "Render HUD into the image (default false)."),
                        param("annotateRange", "integer", false, "Block-annotation cube half-width (1..64, default 16)."),
                        param("format", "string", false, "png (default) | jpeg")),
                resRef("ScreenshotFrame"));

        method(methods, schemas, "world.screenshot_panorama",
                "Take N screenshots around the player in a horizontal sweep (yaw step = 360/N). Returns frames[].",
                params(
                        param("angles", "integer", false, "1..16 (default 4)."),
                        param("width", "integer", false, null),
                        param("yaw", "number", false, "Base yaw; offsets are added per frame."),
                        param("pitch", "number", false, null),
                        param("includeHud", "boolean", false, null),
                        param("annotateRange", "integer", false, null),
                        param("format", "string", false, null)),
                resRef("PanoramaResult"));

        // ----- chat -----
        method(methods, schemas, "chat.send",
                "Send a chat line. Strings starting with / route through sendChatCommand. Fire-and-forget: chat-signing path can hang in offline mode.",
                params(
                        param("text", "string", true, "Message body or /command")),
                resInline(obj("queued", primitive("boolean"))));

        method(methods, schemas, "chat.recent",
                "Read the last N chat lines from the bot's seen-messages buffer (cap 256).",
                params(
                        param("n", "integer", false, "Default 50, max 256.")),
                resRef("ChatMessages"));

        // ----- container -----
        method(methods, schemas, "container.open_inventory",
                "Open the player's own inventory screen (no nearby block needed). Required before SWAP-mode clicks against own inv.",
                params(),
                resInline(obj("opened", primitive("boolean"))));

        method(methods, schemas, "container.open",
                "Right-click a block to open it (chest, barrel, crafting table). Caller polls container.state to confirm.",
                params(
                        param("x", "integer", true, null),
                        param("y", "integer", true, null),
                        param("z", "integer", true, null)),
                resInline(obj("requested", primitive("boolean"))));

        method(methods, schemas, "container.state",
                "Read currently-open screen's slot contents.",
                params(),
                resRef("ContainerState"));

        method(methods, schemas, "container.click",
                "Click a slot in the open screen. SlotActionType: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL.",
                params(
                        param("slot", "integer", true, "Screen slot index. -999 = drop cursor outside."),
                        param("button", "integer", false, "0=left, 1=right, 0..8=hotbar (SWAP), 40=offhand (SWAP)."),
                        param("mode", "string", false, "PICKUP (default), QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL")),
                resInline(obj("clicked", primitive("boolean"))));

        method(methods, schemas, "container.close",
                "Close the open screen.",
                params(),
                resInline(obj("closed", primitive("boolean"))));

        // ----- baritone -----
        method(methods, schemas, "baritone.goto",
                "Pathfind to a goal. Pass {x,y,z} for GoalBlock, {x,z} for GoalXZ, {y} for GoalYLevel.",
                params(
                        param("x", "integer", false, null),
                        param("y", "integer", false, null),
                        param("z", "integer", false, null)),
                resInline(obj("started", primitive("boolean"), "goal", primitive("string"))));

        method(methods, schemas, "baritone.stop",
                "Cancel current pathing.",
                params(),
                resInline(obj("stopped", primitive("boolean"))));

        method(methods, schemas, "baritone.status",
                "Probe baritone availability and current goal.",
                params(),
                resInline(obj(
                        "hasBaritone", primitive("boolean"),
                        "active", primitive("boolean"),
                        "goal", primitive("string"))));

        method(methods, schemas, "baritone.mine",
                "Mine N of the given block ids. WARNING: deadlocks the client thread on this build — prefer baritone.command \"mine <id> [N]\".",
                params(
                        param("blocks", "array", true, "Array of block ids, e.g. [\"minecraft:stone\"]."),
                        param("quantity", "integer", false, "-1 for unlimited (default).")),
                resInline(obj("started", primitive("boolean"), "count", primitive("integer"))));

        method(methods, schemas, "baritone.follow",
                "Follow an entity by display name.",
                params(
                        param("entityName", "string", true, null)),
                resInline(obj("started", primitive("boolean"))));

        method(methods, schemas, "baritone.command",
                "Execute a Baritone chat-command (e.g. \"mine minecraft:stone 5\", \"set allowBreak true\"). Runs on a dedicated worker — does NOT deadlock the client thread like baritone.mine does.",
                params(
                        param("text", "string", true, "Command body without the # prefix.")),
                resInline(obj("queued", primitive("boolean"), "command", primitive("string"))));

        method(methods, schemas, "baritone.get_to_block",
                "Pathfind to the nearest block of the given id (e.g. \"minecraft:chest\"). Bypasses the command parser.",
                params(
                        param("blockId", "string", true, "Full block id with namespace.")),
                resInline(obj("queued", primitive("boolean"), "block", primitive("string"))));

        method(methods, schemas, "baritone.thisway",
                "Walk N blocks along current look direction. No goal-block scan; pure pathfinding test.",
                params(
                        param("distance", "integer", false, "Default 50.")),
                resInline(obj("queued", primitive("boolean"), "distance", primitive("integer"))));

        method(methods, schemas, "baritone.setting",
                "Set a Baritone setting. Value is auto-parsed from JSON or string form.",
                params(
                        param("key", "string", true, "Setting name (lowercased lookup)."),
                        param("value", "string", true, "New value as JSON or text.")),
                resInline(obj("applied", primitive("boolean"), "value", primitive("string"))));

        method(methods, schemas, "baritone.setting_get",
                "Read a Baritone setting's current value.",
                params(
                        param("key", "string", true, null)),
                resInline(obj("key", primitive("string"), "value", primitive("string"))));

        method(methods, schemas, "baritone.build",
                "Schematic build — NOT IMPLEMENTED in v0.1.",
                params(),
                resRef("AnyObject"));

        // ----- meteor -----
        method(methods, schemas, "meteor.modules.list",
                "List all loaded Meteor module names.",
                params(),
                resInline(obj("modules", arr("string"))));

        method(methods, schemas, "meteor.module.enable",
                "Activate a Meteor module by name (e.g. auto-armor, kill-aura).",
                params(
                        param("name", "string", true, null)),
                resInline(obj("ok", primitive("boolean"))));

        method(methods, schemas, "meteor.module.disable",
                "Deactivate a Meteor module by name.",
                params(
                        param("name", "string", true, null)),
                resInline(obj("ok", primitive("boolean"))));

        method(methods, schemas, "meteor.module.toggle",
                "Toggle a Meteor module's active state.",
                params(
                        param("name", "string", true, null)),
                resInline(obj("ok", primitive("boolean"))));

        method(methods, schemas, "meteor.module.is_active",
                "Read whether a module is currently active.",
                params(
                        param("name", "string", true, null)),
                resInline(obj("name", primitive("string"), "active", primitive("boolean"))));

        method(methods, schemas, "meteor.module.settings_list",
                "List the names of all settings on a module.",
                params(
                        param("name", "string", true, null)),
                resInline(obj("name", primitive("string"), "settings", arr("string"))));

        method(methods, schemas, "meteor.module.setting_get",
                "Read a module setting's current value (string form).",
                params(
                        param("name", "string", true, null),
                        param("setting", "string", true, null)),
                resInline(obj(
                        "name", primitive("string"),
                        "setting", primitive("string"),
                        "value", primitive("string"))));

        method(methods, schemas, "meteor.module.setting_set",
                "Set a module setting from a string. Bypasses Meteor's chat-command path which fails silently.",
                params(
                        param("name", "string", true, null),
                        param("setting", "string", true, null),
                        param("value", "string", true, null)),
                resInline(obj("ok", primitive("boolean"))));
    }

    // ===== helpers =====

    private static ObjectNode primitive(String type) {
        ObjectNode n = M.createObjectNode();
        n.put("type", type);
        return n;
    }

    private static ObjectNode primitive(String type, String description) {
        ObjectNode n = primitive(type);
        n.put("description", description);
        return n;
    }

    private static ObjectNode ref(String name) {
        ObjectNode n = M.createObjectNode();
        n.put("$ref", "#/components/schemas/" + name);
        return n;
    }

    private static ObjectNode arr(String itemType) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "array");
        n.set("items", primitive(itemType));
        return n;
    }

    /**
     * Build an object schema from alternating key/value pairs. Values must be
     * ObjectNode (use primitive/ref/arr helpers) OR a String type name.
     */
    private static ObjectNode obj(Object... kv) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "object");
        ObjectNode props = n.putObject("properties");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            Object val = kv[i + 1];
            if (val instanceof ObjectNode on) props.set(key, on);
            else if (val instanceof String s) props.set(key, primitive(s));
            else throw new IllegalArgumentException("bad obj value at " + key);
        }
        return n;
    }

    /** Define a flat type with primitive fields. Args after description: name, type, required, ... */
    private static void type(ObjectNode schemas, String typeName, String description, Object... fields) {
        ObjectNode t = schemas.putObject(typeName);
        t.put("type", "object");
        if (description != null) t.put("description", description);
        ObjectNode props = t.putObject("properties");
        ArrayNode req = M.createArrayNode();
        for (int i = 0; i + 2 < fields.length; i += 3) {
            String fname = (String) fields[i];
            String ftype = (String) fields[i + 1];
            boolean required = (Boolean) fields[i + 2];
            props.set(fname, primitive(ftype));
            if (required) req.add(fname);
        }
        if (!req.isEmpty()) t.set("required", req);
    }

    private static ParamSpec param(String name, String type, boolean required, String description) {
        return new ParamSpec(name, type, required, description, null);
    }

    private static ParamSpec[] params(ParamSpec... ps) {
        return ps;
    }

    /** Single param-bag referencing an existing schema (e.g. AnyObject). */
    private static ParamSpec[] params(String refTypeName) {
        return new ParamSpec[]{new ParamSpec("params", null, false, null, refTypeName)};
    }

    private static ResultSpec resRef(String name) {
        return new ResultSpec(name, null);
    }

    private static ResultSpec resInline(ObjectNode schema) {
        return new ResultSpec(null, schema);
    }

    private static void method(ArrayNode methods, ObjectNode schemas,
                               String name, String summary,
                               ParamSpec[] params, ResultSpec result) {
        ObjectNode m = methods.addObject();
        m.put("name", name);
        m.put("summary", summary);
        // OpenRPC: params is an array. We use the by-name model.
        ArrayNode pa = m.putArray("params");
        for (ParamSpec p : params) {
            ObjectNode po = pa.addObject();
            po.put("name", p.name);
            po.put("required", p.required);
            if (p.description != null) po.put("description", p.description);
            if (p.refType != null) {
                po.set("schema", ref(p.refType));
            } else {
                po.set("schema", primitive(p.type));
            }
        }
        ObjectNode r = m.putObject("result");
        r.put("name", capitalize(name.replace('.', '_')) + "Result");
        if (result.refType != null) {
            r.set("schema", ref(result.refType));
        } else {
            r.set("schema", result.inline);
        }
        m.put("paramStructure", "by-name");
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '.') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    private static final class ParamSpec {
        final String name;
        final String type;
        final boolean required;
        final String description;
        final String refType;

        ParamSpec(String name, String type, boolean required, String description, String refType) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
            this.refType = refType;
        }
    }

    private static final class ResultSpec {
        final String refType;
        final ObjectNode inline;

        ResultSpec(String refType, ObjectNode inline) {
            this.refType = refType;
            this.inline = inline;
        }
    }

    /**
     * Read the bundled openrpc.json from the mod jar's classpath. Cheap;
     * called by rpc.discover. Returns null if the resource isn't packed
     * (in which case the caller should fall back to {@link #buildOpenRpc()}).
     */
    public static ObjectNode loadBundledSpec() {
        try (InputStream in = Schema.class.getResourceAsStream("/btone-openrpc.json")) {
            if (in == null) return null;
            return (ObjectNode) M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Entry point for build-time codegen.
     *   gradle generateOpenRpc → java -cp ... Schema <output-path>
     */
    public static void main(String[] args) throws IOException {
        ObjectNode spec = buildOpenRpc();
        String out = M.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            Files.writeString(Paths.get(args[0]), out);
            System.err.println("wrote " + args[0] + " (" + spec.get("methods").size() + " methods)");
        } else {
            System.out.println(out);
        }
    }
}
