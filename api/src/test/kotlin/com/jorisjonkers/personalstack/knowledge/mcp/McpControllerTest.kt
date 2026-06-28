package com.jorisjonkers.personalstack.knowledge.mcp

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class McpControllerTest {
    private val tools = mockk<McpTools>(relaxed = true)
    private val prompts = mockk<McpPrompts>(relaxed = true)
    private val controller = McpController(tools, prompts)
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `notifications without id reply 202 with empty body so rmcp clients finish the handshake`() {
        // MCP Streamable-HTTP: `notifications/initialized` is a JSON-RPC
        // notification (no `id`). The server MUST return 202 Accepted with
        // an empty body — replying with a JSON-RPC envelope (error or
        // result) makes rmcp-based clients close the transport mid-
        // handshake, which is exactly what broke `codex mcp` against this
        // server.
        val request = JsonRpcRequest(method = "notifications/initialized")

        val response = controller.rpc(request)

        assertThat(response.statusCode.value()).isEqualTo(202)
        assertThat(response.body).isNull()
    }

    @Test
    fun `notification with explicit JSON null id is still treated as a notification`() {
        val request =
            JsonRpcRequest(
                id = mapper.nullNode(),
                method = "notifications/cancelled",
            )

        val response = controller.rpc(request)

        assertThat(response.statusCode.value()).isEqualTo(202)
        assertThat(response.body).isNull()
    }

    @Test
    fun `regular requests still get 200 with a JSON-RPC envelope`() {
        every { tools.describe() } returns emptyList()
        val request =
            JsonRpcRequest(
                id = mapper.readTree("1"),
                method = "tools/list",
            )

        val response = controller.rpc(request)

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body!!
        assertThat(body.id).isEqualTo(request.id)
        assertThat(body.error).isNull()
        @Suppress("UNCHECKED_CAST")
        val result = body.result as Map<String, Any?>
        assertThat(result["tools"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `wrong jsonrpc version on a request with id is reported as invalid request`() {
        val request =
            JsonRpcRequest(
                jsonrpc = "1.0",
                id = mapper.readTree("2"),
                method = "initialize",
            )

        val response = controller.rpc(request)

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body!!
        assertThat(body.error?.code).isEqualTo(JsonRpcErrorCodes.INVALID_REQUEST)
    }
}
