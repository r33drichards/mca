package com.btone.c.events;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Multi-subscriber broadcast bus. Subscribers run synchronously on the emit thread. */
public final class EventBus {
    /** Single emitted event. {@code payload} is JSON-serializable. */
    public record Event(String type, long ts, Map<String, Object> payload) {}

    private final CopyOnWriteArrayList<Consumer<Event>> subs = new CopyOnWriteArrayList<>();

    public void emit(String type, Map<String, Object> payload) {
        Event ev = new Event(type, System.currentTimeMillis(),
                payload == null ? Map.of() : payload);
        for (Consumer<Event> s : subs) {
            try { s.accept(ev); } catch (Throwable ignored) {}
        }
    }

    public void emit(String type) { emit(type, Map.of()); }

    public Closeable subscribe(Consumer<Event> fn) {
        subs.add(fn);
        return () -> subs.remove(fn);
    }

    public int subscriberCount() { return subs.size(); }
}
