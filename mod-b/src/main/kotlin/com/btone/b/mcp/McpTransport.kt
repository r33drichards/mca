package com.btone.b.mcp

import com.btone.b.http.BtoneHttpServer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange

class McpTransport {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun handle(ex: HttpExchange) {
        if (ex.requestMethod != "POST") {
            BtoneHttpServer.write(ex, 405, """{"error":"method not allowed"}""")
            return
        }
        val req = mapper.readTree(ex.requestBody)
        val id = req["id"] ?: mapper.nullNode()
        val method = req["method"]?.asText()
        val params = req["params"] ?: mapper.createObjectNode()

        val resp: ObjectNode = mapper.createObjectNode()
        resp.put("jsonrpc", "2.0")
        resp.set<ObjectNode>("id", id)

        try {
            val result: JsonNode? = when (method) {
                "initialize" -> initializeResult()
                "tools/list" -> listResult()
                "tools/call" -> callResult(params)
                else -> null
            }
            if (result != null) {
                resp.set<ObjectNode>("result", result)
            } else {
                resp.putObject("error").put("code", -32601).put("message", "method not found")
            }
        } catch (t: Throwable) {
            resp.remove("result")
            resp.putObject("error").put("code", -32000).put("message", t.message ?: t.javaClass.simpleName)
        }
        BtoneHttpServer.write(ex, 200, mapper.writeValueAsString(resp))
    }

    private fun initializeResult(): JsonNode = mapper.readTree(
        """{"protocolVersion":"2025-06-18","serverInfo":{"name":"btone-mod-b","version":"0.1.0"},"capabilities":{"tools":{"listChanged":false}}}"""
    )

    private fun listResult(): JsonNode {
        val res = mapper.createObjectNode()
        val arr = res.putArray("tools")
        ToolRegistry.list().forEach { t ->
            val o = arr.addObject()
            o.put("name", t.name)
            o.put("description", t.description)
            o.set<ObjectNode>("inputSchema", t.inputSchema)
        }
        return res
    }

    private fun callResult(params: JsonNode): JsonNode {
        val name = params["name"]?.asText()
            ?: return errorEnvelope("missing tool name")
        val args = params["arguments"] ?: mapper.createObjectNode()
        val tool = ToolRegistry.get(name) ?: return errorEnvelope("unknown tool $name")
        return tool.call(args)
    }

    private fun errorEnvelope(msg: String): JsonNode {
        val o = mapper.createObjectNode()
        o.put("isError", true)
        o.putArray("content").addObject().put("type", "text").put("text", msg)
        return o
    }
}
