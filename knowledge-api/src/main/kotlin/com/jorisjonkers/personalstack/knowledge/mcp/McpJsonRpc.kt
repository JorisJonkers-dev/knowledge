package com.jorisjonkers.personalstack.knowledge.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

/**
 * Minimal JSON-RPC 2.0 envelope shared by every MCP method. The MCP
 * spec layers method semantics on top of this; the shape itself is
 * just plain JSON-RPC.
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val method: String = "",
    val params: JsonNode? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val result: Any? = null,
    val error: JsonRpcError? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
