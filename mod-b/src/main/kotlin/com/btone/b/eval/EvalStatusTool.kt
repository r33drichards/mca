package com.btone.b.eval

import com.btone.b.mcp.McpTool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class EvalStatusTool(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : McpTool {
    override val name = "eval_status"
    override val description = "Fetch status of an async eval job."
    override val inputSchema: ObjectNode = mapper.readTree(
        """
        {
          "type":"object",
          "required":["jobId"],
          "properties":{
            "jobId":{"type":"string"}
          }
        }
        """.trimIndent(),
    ) as ObjectNode

    override fun call(params: JsonNode): JsonNode {
        val jobId = params["jobId"]?.asText()
            ?: return textEnvelope("missing required param: jobId", isError = true)
        val snap = EvalJobRegistry.get(jobId)
            ?: return textEnvelope("unknown jobId", isError = true)
        val payload = mapper.createObjectNode().apply {
            put("state", snap.state)
            set<JsonNode>("value", valueToJson(snap.value))
            put("stdout", snap.stdout)
            put("stderr", snap.stderr)
            if (snap.error != null) put("error", snap.error) else putNull("error")
        }
        val isError = snap.state == "error" || snap.state == "timeout"
        return textEnvelope(mapper.writeValueAsString(payload), isError = isError)
    }

    private fun valueToJson(v: Any?): JsonNode =
        try { mapper.valueToTree(v) } catch (t: Throwable) { mapper.valueToTree(v?.toString()) }

    private fun textEnvelope(text: String, isError: Boolean): JsonNode {
        val o = mapper.createObjectNode()
        if (isError) o.put("isError", true)
        o.putArray("content").addObject().put("type", "text").put("text", text)
        return o
    }
}
