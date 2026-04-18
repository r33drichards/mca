package com.btone.c.handlers;

import com.btone.c.ClientThread;
import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public final class ChatHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final int BUFFER_MAX = 256;
    private static final Deque<String> RECENT = new ArrayDeque<>();

    private ChatHandlers() {}

    /** Called by GameEvents when a chat message is received. */
    public static synchronized void record(String line) {
        RECENT.addLast(line);
        while (RECENT.size() > BUFFER_MAX) RECENT.removeFirst();
    }

    public static void registerAll(RpcRouter r) {
        r.register("chat.send", params -> ClientThread.call(2_000, () -> {
            String text = params.get("text").asText();
            MinecraftClient mc = MinecraftClient.getInstance();
            var p = mc.player;
            var nh = mc.getNetworkHandler();
            if (p == null || nh == null) throw new IllegalStateException("no_player");
            if (text.startsWith("/")) nh.sendChatCommand(text.substring(1));
            else nh.sendChatMessage(text);
            ObjectNode n = M.createObjectNode();
            n.put("sent", true);
            return n;
        }));
        r.register("chat.recent", params -> {
            int requested = params == null || params.isNull() ? 50 : params.path("n").asInt(50);
            int n = Math.max(0, Math.min(requested, BUFFER_MAX));
            ObjectNode root = M.createObjectNode();
            var arr = root.putArray("messages");
            synchronized (ChatHandlers.class) {
                int total = RECENT.size();
                int skip = Math.max(0, total - n);
                Iterator<String> it = RECENT.iterator();
                int i = 0;
                while (it.hasNext()) {
                    String s = it.next();
                    if (i++ < skip) continue;
                    arr.add(s);
                }
            }
            return root;
        });
    }
}
