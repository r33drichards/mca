package com.btone.b.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

interface McpTool {
    val name: String
    val description: String
    val inputSchema: ObjectNode
    // Synchronous for now; no coroutines. Later tasks that need async return jobIds, not suspend.
    fun call(params: JsonNode): JsonNode
}

object ToolRegistry {
    private val tools = linkedMapOf<String, McpTool>()
    fun register(t: McpTool) { tools[t.name] = t }
    fun list(): Collection<McpTool> = tools.values
    fun get(name: String): McpTool? = tools[name]
    // for tests
    internal fun clear() { tools.clear() }
}
