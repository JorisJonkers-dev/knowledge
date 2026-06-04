package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Browser-facing KB governance summary. This is read-only: it
 * aggregates pending/reviewable buckets and proposes admin actions,
 * but destructive changes stay behind explicit admin MCP tools.
 */
@RestController
@RequestMapping("/api/v1/knowledge/review")
class KnowledgeReviewController(
    private val reviewService: ReviewService,
) {
    @GetMapping("/summary")
    fun summary(
        @RequestParam("limit", defaultValue = "${ReviewSummaryRequest.DEFAULT_LIMIT}") limit: Int,
        @RequestParam("stale_days", defaultValue = "${ReviewSummaryRequest.DEFAULT_STALE_DAYS}") staleDays: Int,
        @RequestParam(
            "low_confidence_max",
            defaultValue = "${ReviewSummaryRequest.DEFAULT_LOW_CONFIDENCE_MAX}",
        ) lowConfidenceMax: Double,
        @RequestParam(
            "high_recall_min",
            defaultValue = "${ReviewSummaryRequest.DEFAULT_HIGH_RECALL_MIN}",
        ) highRecallMin: Int,
        @RequestParam(
            "tag_min_count",
            defaultValue = "${ReviewSummaryRequest.DEFAULT_TAG_MIN_COUNT}",
        ) tagMinCount: Int,
        @RequestParam(
            "tag_threshold",
            defaultValue = "${ReviewSummaryRequest.DEFAULT_TAG_THRESHOLD}",
        ) tagThreshold: Double,
        @RequestParam(
            "tag_max_tags",
            defaultValue = "${ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS}",
        ) tagMaxTags: Int,
    ): ReviewSummaryResponse =
        ReviewSummaryResponse.from(
            reviewService.summary(
                ReviewSummaryRequest(
                    limit = limit.coerceIn(1, MAX_LIMIT),
                    staleDays = staleDays.coerceIn(1, MAX_STALE_DAYS),
                    lowConfidenceMax = lowConfidenceMax.coerceIn(0.0, 1.0),
                    highRecallMin = highRecallMin.coerceAtLeast(1),
                    tagMinCount = tagMinCount.coerceAtLeast(1),
                    tagThreshold = tagThreshold.coerceIn(0.0, 1.0),
                    tagMaxTags = tagMaxTags.coerceIn(1, MAX_TAGS),
                ),
            ),
        )

    private companion object {
        const val MAX_LIMIT = 50
        const val MAX_STALE_DAYS = 365
        const val MAX_TAGS = 500
    }
}
