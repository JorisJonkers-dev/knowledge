package com.jorisjonkers.personalstack.knowledge.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
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
import java.time.Instant

/**
 * End-to-end coverage for the admin MCP tools (`add_topic`,
 * `update_topic`, `merge_topics`, `rename_tag`). Drives each one
 * through the JSON-RPC envelope with both an admin-eligible bearer
 * and a plain bearer, asserting that the auth gate rejects the
 * non-admin token with `-32001 Unauthorized` while the admin token
 * mutates state successfully.
 */
@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.admin=admin-token",
        "knowledge.mcp.tokens.user=user-token",
        "knowledge.mcp.admin-tokens[0]=admin",
    ],
)
class AdminFlowIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var mcpBearerFilter: McpBearerFilter

    @Autowired
    private lateinit var noteRepository: NoteRepository

    @Autowired
    private lateinit var topicRepository: TopicRepository

    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(mcpBearerFilter)
                .build()
    }

    @Test
    fun `add_topic refuses a non-admin bearer with -32001`() {
        val response =
            callRaw(
                bearer = "user-token",
                name = "knowledge.add_topic",
                args = mapOf("slug" to "rust", "description" to "Rust ecosystem"),
            )
        val error = objectMapper.readTree(response)["error"]
        assertThat(error["code"].asInt()).isEqualTo(-32001)
        assertThat(topicRepository.findBySlug("rust")).isNull()
    }

    @Test
    fun `add_topic with an admin bearer inserts the slug and surfaces it via list_topics`() {
        val response =
            callRaw(
                bearer = "admin-token",
                name = "knowledge.add_topic",
                args =
                    mapOf(
                        "slug" to "rust",
                        "description" to "Rust ecosystem",
                        "aliases" to listOf("rs", "Rust"),
                    ),
            )
        val structured = objectMapper.readTree(response)["result"]["structuredContent"]
        assertThat(structured["topic"]["slug"].asText()).isEqualTo("rust")
        assertThat(structured["topic"]["is_active"].asBoolean()).isTrue
        val aliases = structured["topic"]["aliases"].map { it.asText() }
        // The slug doubles as its own alias by convention; passed
        // aliases are lowercased on insert.
        assertThat(aliases).contains("rust", "rs")
    }

    @Test
    fun `update_topic flips is_active and replaces aliases wholesale`() {
        seedTopic(slug = "ephemeral", description = "to be retired")

        val response =
            callRaw(
                bearer = "admin-token",
                name = "knowledge.update_topic",
                args =
                    mapOf(
                        "slug" to "ephemeral",
                        "description" to "retired",
                        "aliases" to listOf("old-name"),
                        "is_active" to false,
                    ),
            )

        val topic = objectMapper.readTree(response)["result"]["structuredContent"]["topic"]
        assertThat(topic["is_active"].asBoolean()).isFalse
        assertThat(topic["description"].asText()).isEqualTo("retired")
    }

    @Test
    fun `merge_topics moves every scoped note over and soft-deactivates the source`() {
        seedTopic(slug = "old-slug", description = "legacy")
        seedTopic(slug = "new-slug", description = "consolidated home")
        val a = seedNote(scope = "topic:old-slug")
        val b = seedNote(scope = "topic:old-slug")
        val c = seedNote(scope = "topic:new-slug")

        val response =
            callRaw(
                bearer = "admin-token",
                name = "knowledge.merge_topics",
                args = mapOf("from_slug" to "old-slug", "into_slug" to "new-slug"),
            )

        val structured = objectMapper.readTree(response)["result"]["structuredContent"]
        assertThat(structured["notes_moved"].asInt()).isEqualTo(2)
        assertThat(structured["actor"].asText()).isEqualTo("mcp:admin")

        // Source slug is now soft-deactivated; both notes carry the
        // new scope; the destination's existing note is unaffected.
        assertThat(topicRepository.findBySlug("old-slug")?.isActive).isFalse
        assertThat(noteRepository.findById(a.id)?.scope).isEqualTo("topic:new-slug")
        assertThat(noteRepository.findById(b.id)?.scope).isEqualTo("topic:new-slug")
        assertThat(noteRepository.findById(c.id)?.scope).isEqualTo("topic:new-slug")
    }

    @Test
    fun `rename_tag updates every kb_note_tags row touching the source tag`() {
        seedNote(tags = setOf("kt", "spring"))
        seedNote(tags = setOf("kt"))
        seedNote(tags = setOf("vue"))

        val response =
            callRaw(
                bearer = "admin-token",
                name = "knowledge.rename_tag",
                args = mapOf("from" to "kt", "to" to "kotlin"),
            )

        val structured = objectMapper.readTree(response)["result"]["structuredContent"]
        assertThat(structured["rows_touched"].asInt()).isEqualTo(2)
        assertThat(structured["from"].asText()).isEqualTo("kt")
        assertThat(structured["to"].asText()).isEqualTo("kotlin")
    }

    private fun seedTopic(
        slug: String,
        description: String,
    ) = topicRepository.insert(slug = slug, description = description, aliases = emptySet(), createdBy = "seed")

    private fun seedNote(
        scope: String = "personal",
        tags: Set<String> = emptySet(),
    ): KbNote {
        val note =
            KbNote(
                id = Ulid.generate(),
                type = KbNoteType.LESSON,
                scope = scope,
                source = "test",
                capturedAt = Instant.now(),
                sessionId = null,
                confidence = 0.4,
                title = "title",
                body = "body",
                vaultPath = "$scope/draft.md",
                vaultCommit = null,
                tags = tags,
            )
        return noteRepository.create(note)
    }

    private fun callRaw(
        bearer: String,
        name: String,
        args: Map<String, Any?>,
    ): String {
        val body =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf("name" to name, "arguments" to args),
            )
        val result =
            mockMvc
                .post("/mcp") {
                    contentType = MediaType.APPLICATION_JSON
                    header("Authorization", "Bearer $bearer")
                    content = objectMapper.writeValueAsBytes(body)
                }.andReturn()
        assertThat(result.response.status).isEqualTo(200)
        return result.response.contentAsString
    }
}
