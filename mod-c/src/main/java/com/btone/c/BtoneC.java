package com.btone.c;

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

            RpcRouter router = new RpcRouter();
            router.register("debug.echo", params -> params);
            router.register("debug.methods", params -> {
                ObjectNode n = MAPPER.createObjectNode();
                var arr = n.putArray("methods");
                router.all().keySet().forEach(arr::add);
                return n;
            });

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

            BtoneHttpServer server = new BtoneHttpServer(25591, token, routes);
            server.start();

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
