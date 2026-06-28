package com.jorisjonkers.personalstack.knowledge.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.workstation=test-token-ws",
        "knowledge.mcp.tokens.laptop=test-token-lap",
    ],
)
class McpControllerIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mcpBearerFilter: McpBearerFilter

    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        // `MockMvcBuilders.webAppContextSetup` only wires Spring MVC
        // dispatch — it does *not* auto-mount servlet filters even
        // when those beans are present in the context. Without an
        // explicit `.addFilter(...)`, every /mcp request bypasses
        // McpBearerFilter entirely and would return 200, masking
        // every authn assertion below as silently green.
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(mcpBearerFilter)
                .build()
    }

    @Test
    fun `mcp without authorization header returns 401 with json-rpc error body`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader("WWW-Authenticate")).contains("Bearer")
        val body = objectMapper.readTree(result.response.contentAsString)
        assertThat(body["error"]["code"].asInt()).isEqualTo(-32001)
    }

    @Test
    fun `mcp with wrong bearer token returns 401`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer nope")
                    content = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(401)
    }

    @Test
    fun `mcp ping with valid bearer returns empty result and sets X-User-Id`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = """{"jsonrpc":"2.0","id":42,"method":"ping"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.getHeader("X-User-Id")).isEqualTo("mcp:workstation")
        val body = objectMapper.readTree(result.response.contentAsString)
        assertThat(body["jsonrpc"].asText()).isEqualTo("2.0")
        assertThat(body["id"].asInt()).isEqualTo(42)
        assertThat(body["result"]).isNotNull
        // `error` is omitted entirely on success thanks to
        // `@JsonInclude(NON_NULL)` on JsonRpcResponse.
        assertThat(body.has("error")).isFalse
    }

    @Test
    fun `mcp initialize advertises tools and prompts capabilities plus the protocol version`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-lap")
                    content = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val result0 = objectMapper.readTree(result.response.contentAsString)["result"]
        assertThat(result0["protocolVersion"].asText()).isEqualTo("2025-06-18")
        assertThat(result0["serverInfo"]["name"].asText()).isEqualTo("knowledge-api")
        assertThat(result0["capabilities"]["tools"]["listChanged"].asBoolean()).isFalse
        assertThat(result0["capabilities"]["prompts"]["listChanged"].asBoolean()).isFalse
    }

    @Test
    fun `mcp tools list advertises the registered tools as a JSON-RPC result array`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        // Assert on the contract — `tools` is a JSON array of objects
        // with `name` set — rather than pinning a count. The capture
        // suite (CaptureFlowIntegrationTest) already pins the specific
        // tool names; adding new tools should not regress this test.
        val tools = objectMapper.readTree(result.response.contentAsString)["result"]["tools"]
        assertThat(tools.isArray).isTrue
        assertThat(tools.size()).isGreaterThan(0)
        tools.forEach { assertThat(it["name"].asText()).isNotBlank }
    }

    @Test
    fun `mcp prompts list returns the registered prompts with their argument metadata`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = """{"jsonrpc":"2.0","id":10,"method":"prompts/list"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val prompts = objectMapper.readTree(result.response.contentAsString)["result"]["prompts"]
        assertThat(prompts.isArray).isTrue
        val names = prompts.map { it["name"].asText() }
        assertThat(names).contains("recall_for_task", "capture_lesson_about", "topics_audit")
    }

    @Test
    fun `mcp prompts get renders the recall_for_task message with the supplied task verbatim`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content =
                        """
                        {"jsonrpc":"2.0","id":11,"method":"prompts/get","params":
                          {"name":"recall_for_task","arguments":{"task":"add a Vue component"}}}
                        """.trimIndent()
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val out = objectMapper.readTree(result.response.contentAsString)["result"]
        assertThat(out["messages"].size()).isEqualTo(1)
        val msg = out["messages"][0]
        assertThat(msg["role"].asText()).isEqualTo("user")
        assertThat(msg["content"]["type"].asText()).isEqualTo("text")
        val text = msg["content"]["text"].asText()
        assertThat(text).contains("add a Vue component")
        assertThat(text).contains("knowledge.recall")
        assertThat(text).contains("knowledge.list_topics")
    }

    @Test
    fun `mcp prompts get on an unknown name returns method_not_found`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content =
                        """
                        {"jsonrpc":"2.0","id":12,"method":"prompts/get",
                         "params":{"name":"nonexistent"}}
                        """.trimIndent()
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val error = objectMapper.readTree(result.response.contentAsString)["error"]
        assertThat(error["code"].asInt()).isEqualTo(-32601)
    }

    @Test
    fun `mcp unknown method returns method_not_found error`() {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = """{"jsonrpc":"2.0","id":3,"method":"resources/list"}"""
                }.andReturn()

        assertThat(result.response.status).isEqualTo(200)
        val error = objectMapper.readTree(result.response.contentAsString)["error"]
        assertThat(error["code"].asInt()).isEqualTo(-32601)
    }

    @Test
    fun `non-mcp paths are unaffected by the bearer filter`() {
        // Sanity: actuator/health is reachable from auth-less local
        // calls (the integration-test profile doesn't ship security),
        // and the McpBearerFilter must not interfere with anything
        // outside `/mcp**`.
        val result = mockMvc.post("/api/actuator/health") { contentType = MediaType.APPLICATION_JSON }.andReturn()
        assertThat(result.response.status).isNotEqualTo(401)
    }
}
