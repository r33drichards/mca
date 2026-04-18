package com.btone.b

import com.btone.b.eval.EvalStatusTool
import com.btone.b.eval.EvalTool
import com.btone.b.eval.LiveEvalContext
import com.btone.b.events.EventBus
import com.btone.b.events.GameEvents
import com.btone.b.events.SseEndpoint
import com.btone.b.http.BtoneHttpServer
import com.btone.b.mcp.McpTransport
import com.btone.b.mcp.ToolRegistry
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

class BtoneB : ClientModInitializer {
    companion object { val log = LoggerFactory.getLogger("btone-b") }

    override fun onInitializeClient() {
        val token = Token.generate()
        val transport = McpTransport()

        // Event bus is created here so Task 11/12 can wire client lifecycle events into it.
        val eventBus = EventBus()
        val sse = SseEndpoint(eventBus)

        ToolRegistry.register(EvalTool({ LiveEvalContext(eventBus) }))
        ToolRegistry.register(EvalStatusTool())

        val server = BtoneHttpServer(
            port = 25590,
            token = token,
            routes = mapOf(
                "/health" to { ex -> BtoneHttpServer.write(ex, 200, """{"ok":true}""") },
                "/mcp" to transport::handle,
                "/events" to sse::handle,
            ),
        )
        server.start()
        GameEvents.register(eventBus)
        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            log.info("btone-mod-b stopping, closing http server")
            server.stop()
        }
        val cfgPath = FabricLoader.getInstance().configDir.resolve("btone-bridge.json")
        ConnectionConfig(server.actualPort(), token, "0.1.0").writeTo(cfgPath)
        log.info("btone-mod-b listening on 127.0.0.1:{}; config at {}", server.actualPort(), cfgPath)
    }
}
