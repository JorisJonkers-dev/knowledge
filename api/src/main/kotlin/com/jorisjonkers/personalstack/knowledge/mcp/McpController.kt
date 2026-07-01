// keeping the JsonNode shape across the codebase until a coordinated migration lands.

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.auth.McpAuthorizationError
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Streamable-HTTP MCP transport. Phase 4b ships only the envelope and
 * the three handshake methods — `initialize`, `ping`, and an empty
 * `tools/list`. Phase 4c adds the real tools (recall / capture_lesson
 * / capture_decision / etc.) by extending the dispatcher.
 *
 * Bearer auth runs as a Spring filter before this controller sees the
 * request; the resolved token name lives on the request attribute
 * `knowledge.mcp.user` (`mcp:<name>`) for logging.
 */
@RestController
@RequestMapping("/mcp", produces = [MediaType.APPLICATION_JSON_VALUE])
class McpController(
    private val tools: McpTools,
    private val prompts: McpPrompts,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rpc(
        @RequestBody request: JsonRpcRequest,
    ): ResponseEntity<JsonRpcResponse> =
        // JSON-RPC 2.0 notifications (no `id`) get an empty 202 reply per
        // MCP Streamable-HTTP. Returning a JSON-RPC error here makes
        // rmcp-based clients (Codex, etc.) abort the handshake right after
        // they POST `notifications/initialized`.
        when {
            request.id == null || request.id.isNull -> ResponseEntity.accepted().build()
            request.jsonrpc != "2.0" -> {
                ResponseEntity.ok(invalidRequestResponse(request.id, "jsonrpc field must be \"2.0\""))
            }
            else -> ResponseEntity.ok(dispatch(request))
        }

    private fun dispatch(request: JsonRpcRequest): JsonRpcResponse =
        when (request.method) {
            "initialize" -> JsonRpcResponse(id = request.id, result = handleInitialize())
            "ping" -> JsonRpcResponse(id = request.id, result = emptyMap<String, Any>())
            "tools/list" -> JsonRpcResponse(id = request.id, result = mapOf("tools" to tools.describe()))
            "tools/call" -> handleToolsCall(request)
            "prompts/list" -> JsonRpcResponse(id = request.id, result = mapOf("prompts" to prompts.list()))
            "prompts/get" -> handlePromptsGet(request)
            else -> methodNotFoundResponse(request.id, request.method)
        }

    private fun handlePromptsGet(request: JsonRpcRequest): JsonRpcResponse {
        val name =
            request.params
                ?.get("name")
                ?.asString()
                .orEmpty()
        val arguments = request.params?.get("arguments")
        return when {
            name.isBlank() -> invalidParamsResponse(request.id, "prompts/get: missing required string 'name'")
            else ->
                prompts
                    .get(name, arguments)
                    ?.let { JsonRpcResponse(id = request.id, result = projectPromptResult(it)) }
                    ?: methodNotFoundResponse(request.id, "prompts/get:$name")
        }
    }

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val name =
            request.params
                ?.get("name")
                ?.asString()
                .orEmpty()
        return if (name.isBlank()) {
            invalidParamsResponse(request.id, "tools/call: missing required string 'name'")
        } else {
            dispatchToolCall(request, name)
        }
    }

    private fun dispatchToolCall(
        request: JsonRpcRequest,
        name: String,
    ): JsonRpcResponse {
        val arguments = request.params?.get("arguments")
        return try {
            tools
                .call(name, arguments)
                ?.let { JsonRpcResponse(id = request.id, result = wrapToolResult(it)) }
                ?: methodNotFoundResponse(request.id, "tools/call:$name")
        } catch (exc: McpAuthorizationError) {
            unauthorizedResponse(request.id, exc.message)
        }
    }

    private fun invalidParamsResponse(
        id: JsonNode?,
        message: String,
    ): JsonRpcResponse =
        JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = JsonRpcErrorCodes.INVALID_PARAMS, message = message),
        )

    private fun unauthorizedResponse(
        id: JsonNode?,
        message: String?,
    ): JsonRpcResponse =
        JsonRpcResponse(
            id = id,
            error =
                JsonRpcError(
                    code = JsonRpcErrorCodes.UNAUTHORIZED,
                    message = message ?: "admin tool requires an authorized bearer",
                ),
        )

    /**
     * Wrap a tool's domain-shaped result map in the MCP `CallToolResult`
     * envelope (spec 2025-06-18 §6.10): a `content` array containing at
     * least one text block with the serialised payload, the same payload
     * mirrored under `structuredContent`, and an `isError` flag.
     *
     * Without this wrapping the MCP client (Claude Code, Codex,
     * `@modelcontextprotocol/sdk` clients) sees no `content` entries and
     * renders the call as "completed with no output" — even though the
     * tool actually returned hits / notes / relations. Capture tools
     * suffer the same, just less visibly because the response is rarely
     * read.
     */
    private fun wrapToolResult(result: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "content" to
                listOf(
                    mapOf(
                        "type" to "text",
                        "text" to MAPPER.writeValueAsString(result),
                    ),
                ),
            "structuredContent" to result,
            "isError" to false,
        )

    private fun handleInitialize(): Map<String, Any> =
        mapOf(
            "protocolVersion" to PROTOCOL_VERSION,
            "serverInfo" to
                mapOf(
                    "name" to "knowledge-api",
                    "version" to (System.getenv("SERVICE_VERSION") ?: "unknown"),
                ),
            "capabilities" to
                mapOf(
                    "tools" to mapOf("listChanged" to false),
                    "prompts" to mapOf("listChanged" to false),
                ),
        )

    private fun invalidRequestResponse(
        id: JsonNode?,
        message: String,
    ): JsonRpcResponse =
        JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = JsonRpcErrorCodes.INVALID_REQUEST, message = message),
        )

    private fun methodNotFoundResponse(
        id: JsonNode?,
        method: String,
    ): JsonRpcResponse =
        JsonRpcResponse(
            id = id,
            error =
                JsonRpcError(
                    code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                    message = "method '$method' not implemented",
                ),
        )

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}

private fun projectPromptResult(resolved: PromptResult): Map<String, Any?> =
    mapOf(
        "description" to resolved.description,
        "messages" to
            resolved.messages.map { msg ->
                mapOf(
                    "role" to msg.role,
                    "content" to mapOf("type" to "text", "text" to msg.text),
                )
            },
    )
