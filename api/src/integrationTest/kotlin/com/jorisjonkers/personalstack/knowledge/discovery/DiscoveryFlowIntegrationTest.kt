package com.jorisjonkers.personalstack.knowledge.discovery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
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
 * End-to-end integration tests for the discovery MCP tools
 * (`list_topics`, `list_tags`, `list_scopes`, `list_sources`,
 * `topic_stats`, `list_inbox`). Seeds a handful of notes via
 * [NoteRepository] against the Testcontainers Postgres instance,
 * then drives each tool through the JSON-RPC envelope so the
 * `CallToolResult` wrapping is exercised alongside the SQL
 * aggregations.
 */
@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.ws=test-token-ws",
    ],
)
class DiscoveryFlowIntegrationTest
    @Autowired
    constructor(
        private val context: WebApplicationContext,
        private val mcpBearerFilter: McpBearerFilter,
        private val noteRepository: NoteRepository,
    ) : IntegrationTestBase() {
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

        private fun seed(
            title: String,
            scope: String,
            type: KbNoteType = KbNoteType.LESSON,
            source: String = "test",
            tags: Set<String> = emptySet(),
        ): KbNote {
            val note =
                KbNote(
                    id = Ulid.generate(),
                    type = type,
                    scope = scope,
                    source = source,
                    capturedAt = Instant.now(),
                    sessionId = null,
                    confidence = 0.4,
                    title = title,
                    body = "body for $title",
                    vaultPath = "$scope/${type.wire}/${title.replace(' ', '-')}.md",
                    vaultCommit = null,
                    tags = tags,
                )
            return noteRepository.create(note)
        }

        @Test
        fun listTopicsReturnsSlugsWithTheTopicPrefixStripped() {
            seed("kotlin one", scope = "topic:kotlin")
            seed("kotlin two", scope = "topic:kotlin")
            seed("postgres one", scope = "topic:postgres")
            seed("project note", scope = "project:foo") // must not surface as a topic

            val out = discoveryToolResult(objectMapper, call("knowledge.list_topics", mapOf("limit" to 10)))
            val topics = out["topics"].map { it["slug"].asText() to it["note_count"].asInt() }
            assertThat(topics).containsExactlyInAnyOrder(
                "kotlin" to 2,
                "postgres" to 1,
            )
        }

        @Test
        fun listTagsCountsEachTagAndHonoursTheScopeFilter() {
            seed("a", scope = "project:p1", tags = setOf("kotlin", "spring"))
            seed("b", scope = "project:p1", tags = setOf("kotlin"))
            seed("c", scope = "project:p2", tags = setOf("kotlin", "vue"))

            val unscoped = discoveryToolResult(objectMapper, call("knowledge.list_tags", mapOf("limit" to 10)))
            val all = unscoped["tags"].associate { it["tag"].asText() to it["count"].asInt() }
            assertThat(all["kotlin"]).isEqualTo(KOTLIN_TAG_COUNT)
            assertThat(all["spring"]).isEqualTo(1)
            assertThat(all["vue"]).isEqualTo(1)

            val scoped =
                discoveryToolResult(
                    objectMapper,
                    call("knowledge.list_tags", mapOf("scope" to "project:p1", "limit" to 10)),
                )
            val p1 = scoped["tags"].associate { it["tag"].asText() to it["count"].asInt() }
            assertThat(p1["kotlin"]).isEqualTo(2)
            assertThat(p1).doesNotContainKey("vue")
        }

        @Test
        fun listScopesReturnsEveryDistinctScopeOrderedByNoteCountDesc() {
            seed("a", scope = "project:foo")
            seed("b", scope = "project:foo")
            seed("c", scope = "personal")

            val out = discoveryToolResult(objectMapper, call("knowledge.list_scopes", mapOf("limit" to 10)))
            val scopes = out["scopes"].map { it["scope"].asText() to it["note_count"].asInt() }
            // project:foo (2) sorts before personal (1).
            assertThat(scopes).containsExactly(
                "project:foo" to 2,
                "personal" to 1,
            )
        }

        @Test
        fun listSourcesRollsUpProvenanceMarkers() {
            seed("a", scope = "personal", source = "claude-code")
            seed("b", scope = "personal", source = "claude-code")
            seed("c", scope = "personal", source = "codex")

            val out = discoveryToolResult(objectMapper, call("knowledge.list_sources", mapOf("limit" to 10)))
            val sources = out["sources"].associate { it["source"].asText() to it["count"].asInt() }
            assertThat(sources["claude-code"]).isEqualTo(2)
            assertThat(sources["codex"]).isEqualTo(1)
        }

        @Test
        fun topicStatsReturnsAggregatesIncludingTypeBreakdownAndTopTags() {
            seed("a", scope = "topic:kotlin", type = KbNoteType.LESSON, tags = setOf("spring", "jOOQ"))
            seed("b", scope = "topic:kotlin", type = KbNoteType.LESSON, tags = setOf("spring"))
            seed("c", scope = "topic:kotlin", type = KbNoteType.DECISION, tags = setOf("jOOQ"))
            seed("d", scope = "topic:postgres", type = KbNoteType.LESSON, tags = setOf("pgvector"))

            val out = discoveryToolResult(objectMapper, call("knowledge.topic_stats", mapOf("slug" to "kotlin")))

            val stats = out["stats"]
            assertThat(stats["slug"].asText()).isEqualTo("kotlin")
            assertThat(stats["note_count"].asInt()).isEqualTo(KOTLIN_TOPIC_NOTE_COUNT)
            val breakdown = stats["type_breakdown"]
            assertThat(breakdown["lesson"].asInt()).isEqualTo(2)
            assertThat(breakdown["decision"].asInt()).isEqualTo(1)

            val topTags = stats["top_tags"].map { it["tag"].asText() to it["count"].asInt() }
            // spring + jOOQ both have count 2 — assert containment, not order,
            // because the SQL tiebreak on the join goes by tag name.
            assertThat(topTags).contains("spring" to 2, "jOOQ" to 2)
        }

        @Test
        fun topicStatsReturnsNullStatsWhenTheSlugHasNoNotes() {
            val out = discoveryToolResult(objectMapper, call("knowledge.topic_stats", mapOf("slug" to "nonexistent")))
            assertThat(out["stats"].isNull).isTrue
        }

        @Test
        fun listInboxReturnsNotesThatStillHoldTheInboxSentinelScope() {
            val pending = seed("pending classification", scope = "_inbox")
            seed("already classified", scope = "topic:kotlin")

            val out = discoveryToolResult(objectMapper, call("knowledge.list_inbox", mapOf("limit" to 10)))
            val ids = out["notes"].map { it["id"].asText() }
            assertThat(ids).containsExactly(pending.id)
        }

        private fun call(
            name: String,
            arguments: Map<String, Any?>,
        ): String = rpcRaw("tools/call", mapOf("name" to name, "arguments" to arguments))

        private fun rpcRaw(
            method: String,
            params: Map<String, Any?>?,
        ): String {
            val body =
                buildMap<String, Any?> {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", method)
                    if (params != null) put("params", params)
                }
            val result =
                mockMvc
                    .post("/mcp") {
                        contentType = MediaType.APPLICATION_JSON
                        header("Authorization", "Bearer test-token-ws")
                        content = objectMapper.writeValueAsBytes(body)
                    }.andReturn()
            assertThat(result.response.status).isEqualTo(
                org.springframework.http.HttpStatus.OK
                    .value(),
            )
            return result.response.contentAsString
        }

        private companion object {
            private const val KOTLIN_TAG_COUNT = 3
            private const val KOTLIN_TOPIC_NOTE_COUNT = 3
        }
    }

private fun discoveryToolResult(
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    rawJson: String,
) = objectMapper.readTree(rawJson)["result"]["structuredContent"]
