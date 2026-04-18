package com.btone.c.events;

import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {
    @Test
    void deliversEventsToAllSubscribers() {
        EventBus bus = new EventBus();
        List<EventBus.Event> a = new ArrayList<>();
        List<EventBus.Event> b = new ArrayList<>();
        bus.subscribe(a::add);
        bus.subscribe(b::add);
        bus.emit("chat", Map.of("text", "hi"));
        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals("chat", a.get(0).type());
        assertEquals("hi", a.get(0).payload().get("text"));
    }

    @Test
    void closeRemovesSubscriber() throws Exception {
        EventBus bus = new EventBus();
        List<EventBus.Event> got = new ArrayList<>();
        Closeable c = bus.subscribe(got::add);
        bus.emit("x");
        c.close();
        bus.emit("y");
        assertEquals(1, got.size());
        assertEquals("x", got.get(0).type());
    }

    @Test
    void throwingSubscriberDoesNotBlockOthers() {
        EventBus bus = new EventBus();
        List<EventBus.Event> got = new ArrayList<>();
        bus.subscribe(ev -> { throw new RuntimeException("boom"); });
        bus.subscribe(got::add);
        bus.emit("x");
        assertEquals(1, got.size());
    }

    @Test
    void subscriberCountTracks() {
        EventBus bus = new EventBus();
        assertEquals(0, bus.subscriberCount());
        Closeable c = bus.subscribe(ev -> {});
        assertEquals(1, bus.subscriberCount());
        try { c.close(); } catch (Exception ignored) {}
        assertEquals(0, bus.subscriberCount());
    }
}
