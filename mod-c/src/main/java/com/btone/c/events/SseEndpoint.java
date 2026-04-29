package com.btone.c.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;

/** Server-Sent Events endpoint. One subscription per HTTP request, blocks the request thread. */
public final class SseEndpoint {
    private final EventBus bus;
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEndpoint(EventBus bus) { this.bus = bus; }

    public void handle(HttpExchange ex) {
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
        } catch (Exception e) {
            return;
        }
        OutputStream out = ex.getResponseBody();
        var sub = bus.subscribe(ev -> {
            try {
                String line = "event: " + ev.type() + "\n"
                        + "data: " + mapper.writeValueAsString(ev) + "\n\n";
                out.write(line.getBytes());
                out.flush();
            } catch (Throwable ignored) {
                // client disconnected; will be cleaned up by surrounding loop on next sleep
            }
        });
        try {
            // Heartbeat keeps proxies from idle-timing the connection and lets us
            // detect a dropped client (write throws IOException). Short interval
            // matters: each open stream pins a handler thread, and a dead client
            // doesn't release that thread until the next failed write. With
            // multiple SSE consumers (mc-bridge, mcp-v8 sub-server) this is the
            // difference between freeing a stale slot in 5 seconds vs 30, and
            // the http server has bounded concurrency.
            while (true) {
                Thread.sleep(5_000);
                out.write(": keepalive\n\n".getBytes());
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { sub.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
