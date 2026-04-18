package com.btone.c.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * String-keyed dispatch table for {@code POST /rpc}.
 *
 * Wire format:
 * <pre>
 *   request:  {"method": "foo.bar", "params": { ... }}
 *   response: {"ok": true, "result": ...}
 *             {"ok": false, "error": {"code": "...", "message": "..."}}
 * </pre>
 */
public final class RpcRouter {
    private static final ObjectMapper M = new ObjectMapper();
    private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();

    public synchronized void register(String method, RpcHandler h) {
        handlers.put(method, h);
    }

    public synchronized Map<String, RpcHandler> all() {
        return new LinkedHashMap<>(handlers);
    }

    public ObjectNode dispatch(JsonNode req) {
        String method = req.path("method").asText();
        JsonNode params = req.path("params");
        ObjectNode resp = M.createObjectNode();
        RpcHandler h;
        synchronized (this) { h = handlers.get(method); }
        if (h == null) {
            resp.put("ok", false);
            resp.putObject("error")
                    .put("code", "unknown_method")
                    .put("message", method);
            return resp;
        }
        try {
            // params is a MissingNode (not null) when {"method":"foo"} arrives without
            // a "params" key — JsonNode.path(...) never returns null. Handlers can use
            // params.path(...) freely without NPE.
            JsonNode result = h.handle(params);
            resp.put("ok", true);
            resp.set("result", result == null ? M.nullNode() : result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            resp.removeAll();
            resp.put("ok", false);
            resp.putObject("error")
                    .put("code", cause.getClass().getSimpleName())
                    .put("message", String.valueOf(cause.getMessage()));
        }
        return resp;
    }
}
