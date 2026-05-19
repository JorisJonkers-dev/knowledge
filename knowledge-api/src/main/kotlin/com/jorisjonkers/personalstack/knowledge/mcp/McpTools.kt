package com.jorisjonkers.personalstack.knowledge.mcp

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * MCP `tools/list` + `tools/call` registry. The actual tool sets
 * live in the per-domain `*McpTools` components; this class only
 * composes them and routes by tool name.
 */
@Component
class McpTools(
    captureTools: CaptureMcpTools,
    readTools: ReadMcpTools,
    discoveryTools: DiscoveryMcpTools,
    adminTools: AdminMcpTools,
    digestTools: DigestMcpTools,
) {
    private val tools: Map<String, McpTool> =
        (
            captureTools.tools() +
                readTools.tools() +
                discoveryTools.tools() +
                adminTools.tools() +
                digestTools.tools()
        ).associateBy { it.name }

    fun describe(): List<Map<String, Any?>> = tools.values.map { it.descriptor }

    fun call(
        name: String,
        arguments: JsonNode?,
    ): Map<String, Any?>? = tools[name]?.handler?.invoke(arguments ?: NULL_NODE)

    private companion object {
        private val NULL_NODE: JsonNode = JsonMapper.builder().build().createObjectNode()
    }
}
