package com.btone.b

import com.btone.b.http.BtoneHttpServer
import com.btone.b.mcp.McpTool
import com.btone.b.mcp.McpTransport
import com.btone.b.mcp.ToolRegistry
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

class BtoneB : ClientModInitializer {
    companion object { val log = LoggerFactory.getLogger("btone-b") }

    override fun onInitializeClient() {
        val token = Token.generate()
        val transport = McpTransport()

        ToolRegistry.register(object : McpTool {
            override val name = "echo"
            override val description = "debug echo"
            override val inputSchema: ObjectNode =
                transport.mapper.readTree("""{"type":"object","properties":{"msg":{"type":"string"}}}""") as ObjectNode
            override fun call(params: JsonNode): JsonNode =
                transport.mapper.createObjectNode().apply {
                    putArray("content").addObject()
                        .put("type", "text")
                        .put("text", params["msg"]?.asText() ?: "")
                }
        })

        val server = BtoneHttpServer(
            port = 25590,
            token = token,
            routes = mapOf(
                "/health" to { ex -> BtoneHttpServer.write(ex, 200, """{"ok":true}""") },
                "/mcp" to transport::handle,
            ),
        )
        server.start()
        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            log.info("btone-mod-b stopping, closing http server")
            server.stop()
        }
        val cfgPath = FabricLoader.getInstance().configDir.resolve("btone-bridge.json")
        ConnectionConfig(server.actualPort(), token, "0.1.0").writeTo(cfgPath)
        log.info("btone-mod-b listening on 127.0.0.1:{}; config at {}", server.actualPort(), cfgPath)
    }
}
