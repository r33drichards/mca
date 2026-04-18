package com.btone.c.events;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import com.btone.c.handlers.ChatHandlers;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.HashMap;
import java.util.Map;

/** Wires Fabric / Baritone game events into the {@link EventBus}. */
public final class GameEvents {
    private GameEvents() {}

    public static void register(EventBus bus) {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            String text = msg.getString();
            ChatHandlers.record(text);
            Map<String, Object> p = new HashMap<>();
            p.put("text", text);
            p.put("overlay", overlay);
            bus.emit("chat", p);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            bus.emit("joined");
            // primaryBaritone is null until the player joins, so register after.
            registerBaritonePathListener(bus);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> bus.emit("disconnected"));
    }

    private static void registerBaritonePathListener(EventBus bus) {
        try {
            var b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null) return;
            b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    bus.emit("path", Map.of("event", event.name()));
                }
            });
        } catch (Throwable ignored) {
            // Baritone not present or API mismatch — not fatal.
        }
    }
}
