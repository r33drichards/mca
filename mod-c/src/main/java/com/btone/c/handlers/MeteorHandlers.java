package com.btone.c.handlers;

import com.btone.c.meteor.MeteorFacade;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional handlers — only registered when Meteor classes load.
 *
 * Per the lessons from mod-b: do NOT cache the facade at registration time.
 * Re-resolve on every call (Meteor may not have finished initializing when
 * onInitializeClient ran), and surface a clear error when it's still missing.
 */
public final class MeteorHandlers {
    private static final ObjectMapper M = new ObjectMapper();

    private MeteorHandlers() {}

    public static void registerAll(RpcRouter r) {
        r.register("meteor.modules.list", params -> {
            MeteorFacade f = require();
            ObjectNode root = M.createObjectNode();
            var arr = root.putArray("modules");
            for (String name : f.list()) arr.add(name);
            return root;
        });
        r.register("meteor.module.enable", params -> {
            String name = params.get("name").asText();
            require().toggle(name, true);
            ObjectNode n = M.createObjectNode();
            n.put("ok", true);
            return n;
        });
        r.register("meteor.module.disable", params -> {
            String name = params.get("name").asText();
            require().toggle(name, false);
            ObjectNode n = M.createObjectNode();
            n.put("ok", true);
            return n;
        });
        r.register("meteor.module.toggle", params -> {
            String name = params.get("name").asText();
            require().toggle(name, null);
            ObjectNode n = M.createObjectNode();
            n.put("ok", true);
            return n;
        });
        r.register("meteor.module.is_active", params -> {
            String name = params.get("name").asText();
            ObjectNode n = M.createObjectNode();
            n.put("name", name);
            n.put("active", require().isActive(name));
            return n;
        });

        // ----- settings: list / get / set -----
        // Background: Meteor's `.setting <module> <setting> <value>` chat
        // command silently fails when sent through chat.send (output goes to
        // Meteor's UI overlay, not vanilla chat — no programmatic readback).
        // These RPCs hit Meteor's Setting.parse(String) and Setting.get()
        // directly via reflection, so the agent can configure modules
        // without GUI interaction.
        r.register("meteor.module.settings_list", params -> {
            String name = params.get("name").asText();
            ObjectNode root = M.createObjectNode();
            root.put("name", name);
            var arr = root.putArray("settings");
            for (String s : require().listSettings(name)) arr.add(s);
            return root;
        });
        r.register("meteor.module.setting_get", params -> {
            String name = params.get("name").asText();
            String setting = params.get("setting").asText();
            ObjectNode n = M.createObjectNode();
            n.put("name", name);
            n.put("setting", setting);
            n.put("value", require().getSetting(name, setting));
            return n;
        });
        r.register("meteor.module.setting_set", params -> {
            String name = params.get("name").asText();
            String setting = params.get("setting").asText();
            String value = params.get("value").asText();
            ObjectNode n = M.createObjectNode();
            n.put("ok", require().setSetting(name, setting, value));
            return n;
        });
    }

    private static MeteorFacade require() {
        MeteorFacade f = MeteorFacade.tryGet();
        if (f == null) throw new IllegalStateException("meteor_not_available");
        return f;
    }
}
