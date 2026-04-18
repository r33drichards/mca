package com.btone.c;

import com.btone.c.events.EventBus;
import com.btone.c.events.GameEvents;
import com.btone.c.events.SseEndpoint;
import com.btone.c.handlers.BaritoneHandlers;
import com.btone.c.handlers.ChatHandlers;
import com.btone.c.handlers.ContainerHandlers;
import com.btone.c.handlers.MeteorHandlers;
import com.btone.c.handlers.PlayerHandlers;
import com.btone.c.handlers.VisionHandlers;
import com.btone.c.handlers.WorldReadHandlers;
import com.btone.c.handlers.WorldWriteHandlers;
import com.btone.c.http.BtoneHttpServer;
import com.btone.c.rpc.RpcRouter;
import com.btone.c.util.ConnectionConfig;
import com.btone.c.util.Token;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class BtoneC implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("btone-c");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void onInitializeClient() {
        try {
            String token = Token.generate();

            EventBus eventBus = new EventBus();
            SseEndpoint sse = new SseEndpoint(eventBus);

            RpcRouter router = new RpcRouter();
            router.register("debug.echo", params -> params);
            router.register("debug.methods", params -> {
                ObjectNode n = MAPPER.createObjectNode();
                var arr = n.putArray("methods");
                router.all().keySet().forEach(arr::add);
                return n;
            });

            PlayerHandlers.registerAll(router);
            WorldReadHandlers.registerAll(router);
            ChatHandlers.registerAll(router);
            WorldWriteHandlers.registerAll(router);
            ContainerHandlers.registerAll(router);
            BaritoneHandlers.registerAll(router);
            VisionHandlers.registerAll(router);

            // Optional Meteor surface -- reflection-loaded if Meteor is present.
            // Per the lessons from mod-b: probe with Class.forName at registration
            // time so we know whether to install the routes; the facade itself
            // re-resolves on every call (Meteor may not finish initializing
            // until well after our onInitializeClient runs).
            try {
                Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
                MeteorHandlers.registerAll(router);
                LOG.info("meteor integration enabled");
            } catch (ClassNotFoundException cnfe) {
                LOG.info("meteor not present; meteor.* handlers disabled");
            }

            Map<String, Consumer<HttpExchange>> routes = new LinkedHashMap<>();
            routes.put("/health", ex -> BtoneHttpServer.write(ex, 200, "{\"ok\":true}"));
            routes.put("/rpc", ex -> {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    BtoneHttpServer.write(ex, 405,
                            "{\"ok\":false,\"error\":{\"code\":\"method_not_allowed\"}}");
                    return;
                }
                try {
                    JsonNode req = MAPPER.readTree(ex.getRequestBody());
                    ObjectNode resp = router.dispatch(req);
                    BtoneHttpServer.write(ex, 200, resp.toString());
                } catch (Exception e) {
                    BtoneHttpServer.write(ex, 400,
                            "{\"ok\":false,\"error\":{\"code\":\"bad_request\",\"message\":\""
                                    + safe(e.getMessage()) + "\"}}");
                }
            });
            routes.put("/events", sse::handle);

            BtoneHttpServer server = new BtoneHttpServer(25591, token, routes);
            server.start();

            GameEvents.register(eventBus);

            // Disable vanilla auto-pause when the MC window loses focus.
            // The Bot is meant to run in the background while the agent drives it
            // via RPC; the auto-pause Game Menu was popping up after every alt-tab
            // and after some agent-driven screen interactions.
            ClientLifecycleEvents.CLIENT_STARTED.register(c -> {
                try {
                    c.options.pauseOnLostFocus = false;
                    LOG.info("btone-mod-c: pauseOnLostFocus disabled");
                } catch (Throwable t) {
                    LOG.warn("btone-mod-c: could not disable pauseOnLostFocus", t);
                }
            });

            ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
                LOG.info("btone-mod-c stopping, closing http server");
                server.stop();
            });

            ConnectionConfig cfg = new ConnectionConfig(server.actualPort(), token, "0.1.0");
            var cfgPath = FabricLoader.getInstance().getConfigDir().resolve("btone-bridge.json");
            cfg.writeTo(cfgPath);

            LOG.info("btone-mod-c listening on 127.0.0.1:{}; config at {}",
                    server.actualPort(), cfgPath);
        } catch (Exception e) {
            LOG.error("failed to start btone-mod-c http server", e);
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
