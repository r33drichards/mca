package com.btone.b.events

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange

class SseEndpoint(private val bus: EventBus) {
    private val mapper = jacksonObjectMapper()

    fun handle(ex: HttpExchange) {
        ex.responseHeaders["Content-Type"] = listOf("text/event-stream")
        ex.responseHeaders["Cache-Control"] = listOf("no-cache")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val sub = bus.subscribe { ev ->
            try {
                val line = "event: ${ev.type}\ndata: ${mapper.writeValueAsString(ev)}\n\n"
                out.write(line.toByteArray())
                out.flush()
            } catch (_: Throwable) {}
        }
        try {
            while (true) {
                Thread.sleep(30_000)
                out.write(": keepalive\n\n".toByteArray())
                out.flush()
            }
        } catch (_: Throwable) {
        } finally {
            sub.close()
            try { out.close() } catch (_: Throwable) {}
        }
    }
}
