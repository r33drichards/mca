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
        r.register("chat.send", params -> {
            // Fire-and-forget. sendChatMessage in offline mode triggers chat-signing
            // which can block for seconds; submit().get() would time us out.
            // The agent should use baritone.command for # prefixes, not chat.send,
            // to avoid the chat-signing path entirely.
            String text = params.get("text").asText();
            MinecraftClient.getInstance().execute(() -> {
                var nh = MinecraftClient.getInstance().getNetworkHandler();
                if (nh == null) return;
                if (text.startsWith("/")) nh.sendChatCommand(text.substring(1));
                else nh.sendChatMessage(text);
            });
            ObjectNode n = M.createObjectNode();
            n.put("queued", true);
            return n;
        });
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
