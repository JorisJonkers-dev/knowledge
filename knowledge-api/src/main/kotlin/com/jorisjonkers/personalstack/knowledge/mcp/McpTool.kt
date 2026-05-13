package com.jorisjonkers.personalstack.knowledge.mcp

import tools.jackson.databind.JsonNode

/**
 * Single MCP tool registration: a JSON-Schema descriptor sent to
 * clients on `tools/list` and a handler that turns
 * `params.arguments` into the `tools/call` result body.
 */
class McpTool(
    val descriptor: Map<String, Any?>,
    val handler: (JsonNode) -> Map<String, Any?>,
) {
    val name: String
        get() = descriptor["name"] as? String ?: error("McpTool descriptor missing 'name'")
}

/**
 * Shared `inputSchema` builder so capture and read tools render the
 * same object shape.
 */
internal fun toolDescriptor(
    name: String,
    description: String,
    required: List<String>,
    properties: Map<String, Any>,
): Map<String, Any> =
    mapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to
            mapOf(
                "type" to "object",
                "required" to required,
                "properties" to properties,
            ),
    )
