package com.btone.c.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Loopback-only HTTP server with bearer-token auth applied to every route.
 *
 * The auth check runs before the route handler. Route handlers are responsible
 * for sending their own response; if a handler throws, we send 500 with the
 * error message escaped into JSON.
 */
public final class BtoneHttpServer {
    private final HttpServer server;

    public BtoneHttpServer(int port, String token, Map<String, Consumer<HttpExchange>> routes) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        routes.forEach((path, handler) -> server.createContext(path, ex -> {
            Auth.Result a = Auth.check(ex.getRequestHeaders(), token);
            if (a != Auth.Result.OK) {
                write(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            try {
                handler.accept(ex);
            } catch (Throwable t) {
                write(ex, 500, "{\"error\":\"" + escape(t.getMessage()) + "\"}");
            }
        }));
    }

    public int actualPort() { return server.getAddress().getPort(); }
    public void start() {
        // A cached pool is critical: SSE handlers block in Thread.sleep
        // for keepalives, so each open SSE stream pins a thread. The default
        // null executor only fires up a small handful of threads and starves
        // out concurrent RPC calls (rpc.discover etc) once a couple of SSE
        // consumers attach. Cached pool lets each SSE stream and each RPC
        // request live on its own thread.
        server.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "btone-http-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }));
        server.start();
    }
    public void stop() { server.stop(0); }

    public static void write(HttpExchange ex, int code, String body) {
        write(ex, code, body, "application/json");
    }

    public static void write(HttpExchange ex, int code, String body, String contentType) {
        try {
            byte[] bytes = body.getBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // client gone
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
