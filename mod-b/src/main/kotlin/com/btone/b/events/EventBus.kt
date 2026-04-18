package com.btone.b.events

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

data class Event(val type: String, val ts: Long, val payload: Map<String, Any?>)

class EventBus {
    private val subs = CopyOnWriteArrayList<(Event) -> Unit>()

    fun emit(type: String, payload: Map<String, Any?> = emptyMap()) {
        val ev = Event(type, System.currentTimeMillis(), payload)
        subs.forEach { runCatching { it(ev) } }
    }

    fun subscribe(fn: (Event) -> Unit): Closeable {
        subs.add(fn)
        return Closeable { subs.remove(fn) }
    }
}
