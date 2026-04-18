package com.btone.c.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcRouterTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void dispatchesAndPassesParams() {
        RpcRouter r = new RpcRouter();
        r.register("echo", params -> params);
        ObjectNode req = M.createObjectNode();
        req.put("method", "echo");
        req.putObject("params").put("x", 1);
        ObjectNode resp = r.dispatch(req);
        assertTrue(resp.get("ok").asBoolean());
        assertEquals(1, resp.get("result").get("x").asInt());
    }

    @Test
    void unknownMethodReturnsError() {
        RpcRouter r = new RpcRouter();
        ObjectNode req = M.createObjectNode();
        req.put("method", "nope");
        ObjectNode resp = r.dispatch(req);
        assertFalse(resp.get("ok").asBoolean());
        assertEquals("unknown_method", resp.get("error").get("code").asText());
        assertEquals("nope", resp.get("error").get("message").asText());
    }

    @Test
    void handlerExceptionsBecomeError() {
        RpcRouter r = new RpcRouter();
        r.register("boom", params -> { throw new IllegalStateException("nope"); });
        ObjectNode req = M.createObjectNode();
        req.put("method", "boom");
        ObjectNode resp = r.dispatch(req);
        assertFalse(resp.get("ok").asBoolean());
        assertEquals("IllegalStateException", resp.get("error").get("code").asText());
        assertEquals("nope", resp.get("error").get("message").asText());
    }

    @Test
    void missingParamsIsMissingNodeNotNull() {
        // Jackson returns a MissingNode (not null) when a field is absent. Handlers
        // can use params.path(...) safely without NPE; isMissingNode() is true.
        RpcRouter r = new RpcRouter();
        r.register("none", params -> {
            assertNotNull(params);
            assertTrue(params.isMissingNode());
            return M.createObjectNode().put("seen", true);
        });
        ObjectNode req = M.createObjectNode();
        req.put("method", "none");
        ObjectNode resp = r.dispatch(req);
        assertTrue(resp.get("ok").asBoolean());
        assertTrue(resp.get("result").get("seen").asBoolean());
    }

    @Test
    void canEnumerateRegisteredMethods() {
        RpcRouter r = new RpcRouter();
        r.register("a", p -> p);
        r.register("b", p -> p);
        assertEquals(2, r.all().size());
        assertTrue(r.all().containsKey("a"));
        assertTrue(r.all().containsKey("b"));
    }
}
