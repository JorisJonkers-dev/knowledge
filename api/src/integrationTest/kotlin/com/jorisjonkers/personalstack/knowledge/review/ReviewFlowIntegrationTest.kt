package com.jorisjonkers.personalstack.knowledge.review

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
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
import java.time.LocalDateTime
import java.time.ZoneOffset

@TestPropertySource(
    properties = [
        "knowledge.mcp.tokens.ws=test-token-ws",
    ],
)
class ReviewFlowIntegrationTest
    @Autowired
    constructor(
        private val context: WebApplicationContext,
        private val mcpBearerFilter: McpBearerFilter,
        private val noteRepository: NoteRepository,
        private val dsl: DSLContext,
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

        @Test
        fun reviewSummaryReturnsBoundedGovernanceBucketsAndAdvisorySuggestions() {
            val inbox =
                seed(ReviewSeed("plain inbox", scope = "_inbox", vaultPath = "_inbox/2026-06-04/plain.md"))
            val needsReview =
                seed(ReviewSeed("needs review", scope = "_inbox", vaultPath = "_inbox/_needs-review/note.md"))
            val wildcardLookalike =
                seed(ReviewSeed("wildcard lookalike", scope = "_inbox", vaultPath = "xinbox/xneeds-review/note.md"))
            seed(ReviewSeed("recent auto", scope = "project:personal-stack", source = "assistant-ui:auto-capture:s1"))
            seed(
                ReviewSeed(
                    "stale old note",
                    scope = "project:personal-stack",
                    capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            val lowConfidence =
                seed(
                    ReviewSeed(
                        "low confidence useful note",
                        scope = "project:personal-stack",
                        confidence = 0.4,
                    ),
                )
            bumpRecall(lowConfidence.id, count = 5)

            val out =
                toolResult(
                    call(
                        "knowledge.review_summary",
                        mapOf(
                            "limit" to 10,
                            "stale_days" to 30,
                            "high_recall_min" to 3,
                            "tag_max_tags" to 1,
                        ),
                    ),
                )

            val summary = out["summary"]
            assertThat(summary["needs_review"]["total"].asInt()).isEqualTo(1)
            val needsReviewIds = summary["needs_review"]["items"].map { it["id"].asText() }
            assertThat(needsReviewIds).contains(needsReview.id)
            assertThat(needsReviewIds).doesNotContain(wildcardLookalike.id)
            assertThat(summary["inbox"]["total"].asInt()).isEqualTo(2)
            val inboxIds = summary["inbox"]["items"].map { it["id"].asText() }
            assertThat(inboxIds).contains(inbox.id, wildcardLookalike.id)
            assertThat(inboxIds).doesNotContain(needsReview.id)

            val autoSources = summary["recent_auto_captures"]["items"].map { it["source"].asText() }
            assertThat(autoSources).contains("assistant-ui:auto-capture:s1")

            val lowConfidenceIds = summary["low_confidence_high_recall"]["items"].map { it["id"].asText() }
            assertThat(lowConfidenceIds).contains(lowConfidence.id)

            val suggestionKinds = summary["suggestions"].map { it["kind"].asText() }
            assertThat(suggestionKinds).contains("needs_review", "low_confidence_high_recall")
        }

        private fun seed(seed: ReviewSeed): KbNote {
            val note =
                KbNote(
                    id = Ulid.generate(),
                    type = KbNoteType.LESSON,
                    scope = seed.scope,
                    source = seed.source,
                    capturedAt = seed.capturedAt,
                    sessionId = null,
                    confidence = seed.confidence,
                    title = seed.title,
                    body = "body for ${seed.title}",
                    vaultPath = seed.vaultPath,
                    vaultCommit = null,
                )
            return noteRepository.create(note)
        }

        private fun bumpRecall(
            id: String,
            count: Int,
        ) {
            dsl
                .update(KB_NOTES)
                .set(KB_NOTES.RECALL_COUNT, count)
                .set(KB_NOTES.LAST_RECALLED_AT, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .where(KB_NOTES.ID.eq(id))
                .execute()
        }

        private fun call(
            name: String,
            arguments: Map<String, Any?>,
        ): String = rpcRaw("tools/call", mapOf("name" to name, "arguments" to arguments))

        private fun toolResult(rawJson: String) = objectMapper.readTree(rawJson)["result"]["structuredContent"]

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
    }

private data class ReviewSeed(
    val title: String,
    val scope: String,
    val source: String = "test",
    val confidence: Double = 0.8,
    val capturedAt: Instant = Instant.now(),
    val vaultPath: String = "$scope/lesson/${title.replace(' ', '-')}.md",
)
