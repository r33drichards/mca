package com.btone.b.events

import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {
    @Test fun `delivers events to all subscribers`() {
        val bus = EventBus()
        val a = CopyOnWriteArrayList<Event>()
        val b = CopyOnWriteArrayList<Event>()
        bus.subscribe { a.add(it) }
        bus.subscribe { b.add(it) }

        bus.emit("chat", mapOf("text" to "hello"))
        bus.emit("joined")

        assertEquals(2, a.size)
        assertEquals(2, b.size)
        assertEquals("chat", a[0].type)
        assertEquals("hello", a[0].payload["text"])
        assertEquals("joined", a[1].type)
        assertTrue(a[0].ts > 0)
    }

    @Test fun `unsubscribe stops delivery`() {
        val bus = EventBus()
        val captured = CopyOnWriteArrayList<Event>()
        val sub = bus.subscribe { captured.add(it) }

        bus.emit("first")
        sub.close()
        bus.emit("second")

        assertEquals(1, captured.size)
        assertEquals("first", captured[0].type)
    }
}
