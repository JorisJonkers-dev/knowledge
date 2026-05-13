package com.jorisjonkers.personalstack.knowledge.mcp

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

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
class McpController {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rpc(
        @RequestBody request: JsonRpcRequest,
    ): JsonRpcResponse {
        if (request.jsonrpc != "2.0") {
            return invalidRequestResponse(request.id, "jsonrpc field must be \"2.0\"")
        }
        return dispatch(request)
    }

    private fun dispatch(request: JsonRpcRequest): JsonRpcResponse =
        when (request.method) {
            "initialize" -> JsonRpcResponse(id = request.id, result = handleInitialize())
            "ping" -> JsonRpcResponse(id = request.id, result = emptyMap<String, Any>())
            "tools/list" -> JsonRpcResponse(id = request.id, result = mapOf("tools" to emptyList<Any>()))
            else -> methodNotFoundResponse(request.id, request.method)
        }

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
    }
}
