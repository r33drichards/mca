package com.btone.b.eval

import com.btone.b.ClientThread
import com.btone.b.mcp.McpTool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class EvalTool(
    private val ctxFactory: () -> LiveEvalContext,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : McpTool {
    override val name = "eval"
    override val description = "Compile and run Kotlin in the Minecraft client JVM. " +
        "Implicit receivers: api (BtoneApi), events (EventBus), registerCleanup. " +
        "Return value of last expression (if JSON-serializable) appears in result.value. " +
        "If async:true, MC access must be wrapped via api.* (which dispatches to the client thread)."

    override val inputSchema: ObjectNode = mapper.readTree(
        """
        {
          "type":"object",
          "required":["source"],
          "properties":{
            "source":{"type":"string"},
            "timeout_ms":{"type":"integer","default":10000},
            "async":{"type":"boolean","default":false}
          }
        }
        """.trimIndent(),
    ) as ObjectNode

    override fun call(params: JsonNode): JsonNode {
        val source = params["source"]?.asText()
            ?: return errorEnvelope("missing required param: source")
        val timeoutMs = params["timeout_ms"]?.asLong() ?: 10_000L
        val async = params["async"]?.asBoolean() ?: false

        if (async) {
            return try {
                val jobId = EvalJobRegistry.start(source, timeoutMs, ctxFactory)
                val payload = mapper.createObjectNode().put("jobId", jobId)
                textEnvelope(mapper.writeValueAsString(payload), isError = false)
            } catch (t: Throwable) {
                errorEnvelope("failed to start async eval: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        val res = try {
            val ctx = ctxFactory()
            ClientThread.call(timeoutMs) { EvalHost.eval(source, ctx) }
        } catch (t: Throwable) {
            return errorEnvelope("eval dispatch failed: ${t.message ?: t.javaClass.simpleName}")
        }

        val payload = mapper.createObjectNode().apply {
            put("ok", res.ok)
            set<JsonNode>("value", valueToJson(res.value))
            put("stdout", res.stdout)
            put("stderr", res.stderr)
            if (res.errorMsg != null) put("error", res.errorMsg) else putNull("error")
        }
        return textEnvelope(mapper.writeValueAsString(payload), isError = !res.ok)
    }

    private fun valueToJson(v: Any?): JsonNode =
        try { mapper.valueToTree(v) } catch (t: Throwable) { mapper.valueToTree(v?.toString()) }

    private fun textEnvelope(text: String, isError: Boolean): JsonNode {
        val o = mapper.createObjectNode()
        if (isError) o.put("isError", true)
        o.putArray("content").addObject().put("type", "text").put("text", text)
        return o
    }

    private fun errorEnvelope(msg: String): JsonNode {
        val o = mapper.createObjectNode()
        o.put("isError", true)
        o.putArray("content").addObject().put("type", "text").put("text", msg)
        return o
    }
}
