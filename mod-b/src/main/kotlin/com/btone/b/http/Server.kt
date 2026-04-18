package com.btone.b.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class BtoneHttpServer(
    port: Int,
    private val token: String,
    private val routes: Map<String, (HttpExchange) -> Unit>,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    init {
        routes.forEach { (path, handler) ->
            server.createContext(path, HttpHandler { ex ->
                when (Auth.check(ex.requestHeaders, token)) {
                    AuthResult.Ok -> try {
                        handler(ex)
                    } catch (t: Throwable) {
                        write(ex, 500, """{"error":"${t.message}"}""")
                    }
                    else -> write(ex, 401, """{"error":"unauthorized"}""")
                }
            })
        }
    }

    fun actualPort(): Int = server.address.port
    fun start() { server.executor = null; server.start() }
    fun stop() { server.stop(0) }

    companion object {
        fun write(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            ex.responseHeaders["Content-Type"] = listOf(contentType)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
