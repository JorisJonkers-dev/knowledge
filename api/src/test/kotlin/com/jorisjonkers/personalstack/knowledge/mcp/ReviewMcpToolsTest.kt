package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.auth.AdminAuthorization
import com.jorisjonkers.personalstack.knowledge.capture.CaptureService
import com.jorisjonkers.personalstack.knowledge.digest.DigestService
import com.jorisjonkers.personalstack.knowledge.discovery.DiscoveryService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import com.jorisjonkers.personalstack.knowledge.repo.AuditRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.TopicRepository
import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class ReviewMcpToolsTest {
    private val reviewService = mockk<ReviewService>(relaxed = true)
    private val tools = reviewTools(reviewService)
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `review_summary forwards bounded request and projects governance buckets`() {
        val request = slot<ReviewSummaryRequest>()
        every { reviewService.summary(capture(request)) } returns reviewSummary()

        val out =
            tools.call(
                "knowledge.review_summary",
                mapper.readTree(
                    """
                    {
                      "limit": 5,
                      "stale_days": 30,
                      "low_confidence_max": 0.5,
                      "high_recall_min": 2,
                      "tag_threshold": 0.9
                    }
                    """.trimIndent(),
                ),
            )!!

        assertReviewRequest(request.captured)
        assertReviewSummary(out["summary"].stringKeyMap())
    }

    private fun assertReviewRequest(request: ReviewSummaryRequest) {
        assertThat(request.limit).isEqualTo(5)
        assertThat(request.staleDays).isEqualTo(30)
        assertThat(request.lowConfidenceMax).isEqualTo(0.5)
        assertThat(request.highRecallMin).isEqualTo(2)
        assertThat(request.tagThreshold).isEqualTo(0.9)
    }

    private fun assertReviewSummary(summary: Map<String, Any?>) {
        assertThat(summary["generated_at"]).isEqualTo("2026-06-04T16:00:00Z")
        val inbox = summary["inbox"].stringKeyMap()
        assertThat(inbox["total"]).isEqualTo(1)
        val inboxItems = inbox["items"].stringKeyMapList()
        assertThat(inboxItems[0]["source"]).isEqualTo("assistant-ui:auto-capture:s1")
        assertThat(inboxItems[0]["recall_count"]).isEqualTo(4)
        assertThat(inboxItems[0]["last_recalled_at"]).isEqualTo("2026-06-04T15:30:00Z")
        val suggestions = summary["suggestions"].stringKeyMapList()
        assertThat(suggestions[0]["suggested_tool"]).isEqualTo("knowledge.reclassify_note")
    }

    private fun reviewSummary(): ReviewSummary =
        ReviewSummary(
            generatedAt = Instant.parse("2026-06-04T16:00:00Z"),
            inbox = ReviewBucket(total = 1, items = listOf(reviewNote())),
            needsReview = ReviewBucket(total = 0, items = emptyList()),
            recentAutoCaptures = ReviewBucket(total = 0, items = emptyList()),
            staleUnusedNotes = ReviewBucket(total = 0, items = emptyList()),
            lowConfidenceHighRecall = ReviewBucket(total = 0, items = emptyList()),
            tagCandidateClusters = ReviewBucket(total = 0, items = emptyList()),
            recentAudit = ReviewBucket(total = 0, items = emptyList()),
            suggestions = listOf(reviewSuggestion()),
        )

    private fun reviewNote(): ReviewNote =
        ReviewNote(
            id = "01HXREVIEW0000000000000000",
            type = "lesson",
            scope = "_inbox",
            source = "assistant-ui:auto-capture:s1",
            capturedAt = Instant.parse("2026-06-04T15:00:00Z"),
            confidence = 0.52,
            title = "pending note",
            vaultPath = "_inbox/2026-06-04/pending.md",
            tags = setOf("auto-capture"),
            recallCount = 4,
            lastRecalledAt = Instant.parse("2026-06-04T15:30:00Z"),
        )

    private fun reviewSuggestion(): ReviewSuggestion =
        ReviewSuggestion(
            kind = "needs_review",
            severity = "high",
            message = "review pending memory",
            suggestedTool = "knowledge.reclassify_note",
            targetId = "01HXREVIEW0000000000000000",
            targetKind = "note",
            details = mapOf("total" to 1),
        )

    private fun reviewTools(reviewService: ReviewService): McpTools =
        McpTools(
            coreTools =
                CoreMcpToolSet(
                    CaptureMcpTools(mockk<CaptureService>(relaxed = true)),
                    ReadMcpTools(mockk<RecallService>(relaxed = true)),
                ),
            fullTools =
                FullMcpToolSet(
                    DiscoveryMcpTools(
                        mockk<DiscoveryService>(relaxed = true),
                        mockk<TagClusterService>(relaxed = true),
                    ),
                    AdminMcpTools(
                        mockk<TopicRepository>(relaxed = true),
                        mockk<NoteRepository>(relaxed = true),
                        mockk<AuditRepository>(relaxed = true),
                        mockk<AdminAuthorization>(relaxed = true),
                    ),
                    DigestMcpTools(mockk<DigestService>(relaxed = true)),
                    AuditMcpTools(mockk<AuditService>(relaxed = true)),
                    ReviewMcpTools(reviewService),
                ),
        )
}
