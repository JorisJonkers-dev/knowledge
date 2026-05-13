@file:Suppress("DEPRECATION") // Jackson 3 asText() — see McpTools file header.

package com.jorisjonkers.personalstack.knowledge.capture

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.queue.IngestQueueConfig
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.ws=test-token-ws",
    ],
)
class CaptureFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mcpBearerFilter: McpBearerFilter

    @Autowired
    private lateinit var noteRepository: NoteRepository

    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    @Autowired
    private lateinit var messageConverter: MessageConverter

    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(mcpBearerFilter)
                .build()
        // Drain the bound queue so earlier tests don't bleed messages
        // into the assertions below. `receive(queue, timeout)` is the
        // public Spring-AMQP API for a non-blocking poll; the
        // setter-style `receiveTimeout` is package-private in 4.x.
        while (rabbitTemplate.receive(IngestQueueConfig.QUEUE, DRAIN_TIMEOUT_MS) != null) Unit
    }

    @Test
    fun `tools list advertises the three capture tools`() {
        val body = postRpc(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))
        val tools = objectMapper.readTree(body)["result"]["tools"]
        assertThat(tools.isArray).isTrue
        val names = tools.map { it["name"].asText() }
        assertThat(names)
            .contains(
                "knowledge.capture_lesson",
                "knowledge.capture_decision",
                "knowledge.ingest_note",
            )
    }

    @Test
    fun `capture_lesson persists a kb_notes row and publishes to knowledge_lesson`() {
        val before = noteRepository.rowCount()

        val response =
            postRpc(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 7,
                    "method" to "tools/call",
                    "params" to
                        mapOf(
                            "name" to "knowledge.capture_lesson",
                            "arguments" to
                                mapOf(
                                    "scope" to "personal",
                                    "title" to "lesson title",
                                    "body" to "lesson body",
                                    "tags" to listOf("kotlin", "mcp"),
                                ),
                        ),
                ),
            )

        val result = objectMapper.readTree(response)["result"]
        val id = result["id"].asText()
        assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}")
        assertThat(result["type"].asText()).isEqualTo("lesson")
        assertThat(result["scope"].asText()).isEqualTo("personal")

        assertThat(noteRepository.rowCount()).isEqualTo(before + 1)
        val row = noteRepository.findByIdRaw(id)!!
        assertThat(row["title"]).isEqualTo("lesson title")
        assertThat(row["type"]).isEqualTo("lesson")
        assertThat(noteRepository.tagsOf(id)).containsExactlyInAnyOrder("kotlin", "mcp")

        val (routingKey, payload) = await1QueueMessage()
        assertThat(routingKey).isEqualTo(IngestQueueConfig.ROUTING_LESSON)
        assertThat(payload["id"]).isEqualTo(id)
        assertThat(payload["type"]).isEqualTo("lesson")
        @Suppress("UNCHECKED_CAST")
        assertThat(payload["tags"] as List<String>).containsExactlyInAnyOrder("kotlin", "mcp")
    }

    @Test
    fun `capture_decision routes to knowledge_decision and inserts type=decision`() {
        postRpc(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 8,
                "method" to "tools/call",
                "params" to
                    mapOf(
                        "name" to "knowledge.capture_decision",
                        "arguments" to
                            mapOf(
                                "scope" to "project:personal-stack",
                                "title" to "decision title",
                                "body" to "decision body",
                            ),
                    ),
            ),
        )
        val (routingKey, payload) = await1QueueMessage()
        assertThat(routingKey).isEqualTo(IngestQueueConfig.ROUTING_DECISION)
        assertThat(payload["type"]).isEqualTo("decision")
        val row = noteRepository.findByIdRaw(payload["id"] as String)!!
        assertThat(row["type"]).isEqualTo("decision")
        assertThat(row["scope"]).isEqualTo("project:personal-stack")
    }

    @Test
    fun `ingest_note honours the requested type and routes to knowledge_ingest`() {
        postRpc(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 9,
                "method" to "tools/call",
                "params" to
                    mapOf(
                        "name" to "knowledge.ingest_note",
                        "arguments" to
                            mapOf(
                                "scope" to "agent:claude",
                                "title" to "URL clip",
                                "body" to "https://example.com",
                                "type" to "fact",
                            ),
                    ),
            ),
        )
        val (routingKey, payload) = await1QueueMessage()
        assertThat(routingKey).isEqualTo(IngestQueueConfig.ROUTING_INGEST)
        assertThat(payload["type"]).isEqualTo("fact")
    }

    @Test
    fun `tools_call with an unknown name returns method_not_found`() {
        val response =
            postRpc(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 10,
                    "method" to "tools/call",
                    "params" to
                        mapOf(
                            "name" to "knowledge.no_such_tool",
                            "arguments" to mapOf<String, Any>(),
                        ),
                ),
            )
        val error = objectMapper.readTree(response)["error"]
        assertThat(error["code"].asInt()).isEqualTo(-32601)
    }

    private fun postRpc(envelope: Map<String, Any?>): String {
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer test-token-ws")
                    content = objectMapper.writeValueAsBytes(envelope)
                }.andReturn()
        assertThat(result.response.status).isEqualTo(200)
        return result.response.contentAsString
    }

    private fun await1QueueMessage(): Pair<String, Map<String, Any?>> {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        var msg: Message? = null
        while (msg == null && System.nanoTime() < deadline) {
            msg = rabbitTemplate.receive(IngestQueueConfig.QUEUE)
            if (msg == null) Thread.sleep(POLL_MS)
        }
        check(msg != null) { "no message arrived on ${IngestQueueConfig.QUEUE} within 5s" }
        val deserialized = messageConverter.fromMessage(msg)
        val routingKey = msg.messageProperties.receivedRoutingKey ?: error("no routing key on received message")
        @Suppress("UNCHECKED_CAST")
        return routingKey to (deserialized as Map<String, Any?>)
    }

    companion object {
        private const val POLL_MS = 50L
        private const val DRAIN_TIMEOUT_MS = 50L
    }
}
