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
    coreTools: CoreMcpToolSet,
    fullTools: FullMcpToolSet,
    modeProperties: KnowledgeModeProperties = KnowledgeModeProperties(),
) {
    private val tools: Map<String, McpTool> =
        buildList {
            // Core retrieval + capture surface — always registered.
            addAll(coreTools.tools())
            // Curator-governance surface — only in full mode. `lite` keeps a
            // thin recall+capture service (the lightweight-memory target).
            if (modeProperties.mode == KnowledgeMode.FULL) {
                addAll(fullTools.tools())
            }
        }.associateBy { it.name }

    fun describe(): List<Map<String, Any?>> = tools.values.map { it.descriptor }

    fun call(
        name: String,
        arguments: JsonNode?,
    ): Map<String, Any?>? = tools[name]?.handler?.invoke(arguments ?: NULL_NODE)

    private companion object {
        private val NULL_NODE: JsonNode = JsonMapper.builder().build().createObjectNode()
    }
}

@Component
class CoreMcpToolSet(
    private val captureTools: CaptureMcpTools,
    private val readTools: ReadMcpTools,
) {
    fun tools(): List<McpTool> = captureTools.tools() + readTools.tools()
}

@Component
class FullMcpToolSet(
    private val discoveryTools: DiscoveryMcpTools,
    private val adminTools: AdminMcpTools,
    private val digestTools: DigestMcpTools,
    private val auditTools: AuditMcpTools,
    private val reviewTools: ReviewMcpTools,
) {
    fun tools(): List<McpTool> =
        discoveryTools.tools() +
            adminTools.tools() +
            digestTools.tools() +
            auditTools.tools() +
            reviewTools.tools()
}
