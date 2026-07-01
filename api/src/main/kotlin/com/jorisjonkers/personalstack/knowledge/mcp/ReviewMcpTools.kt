package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class ReviewMcpTools(
    private val reviewService: ReviewService,
) {
    fun tools(): List<McpTool> = listOf(McpTool(reviewSummaryDescriptor(), ::reviewSummaryHandler))

    private fun reviewSummaryDescriptor(): Map<String, Any> =
        toolDescriptor(
            name = "knowledge.review_summary",
            description =
                "Read-only KB governance summary for review workflows. Returns bounded " +
                    "buckets for `_inbox`, `_needs-review`, recent auto-captures, " +
                    "near-duplicate tags, stale unused notes, low-confidence notes with " +
                    "high recall counts, recent audit rows, and suggested next actions. " +
                    "Suggestions are advisory only; run admin tools separately after " +
                    "operator review.",
            required = emptyList(),
            properties = reviewSummaryProperties(),
        )

    private fun reviewSummaryProperties(): Map<String, Any> =
        mapOf(
            "limit" to reviewIntSchema(default = ReviewSummaryRequest.DEFAULT_LIMIT, maximum = REVIEW_MAX_LIMIT),
            "stale_days" to
                reviewIntSchema(
                    default = ReviewSummaryRequest.DEFAULT_STALE_DAYS,
                    maximum = REVIEW_MAX_STALE_DAYS,
                ),
            "low_confidence_max" to boundedReviewNumberSchema(ReviewSummaryRequest.DEFAULT_LOW_CONFIDENCE_MAX),
            "high_recall_min" to
                reviewIntSchema(
                    default = ReviewSummaryRequest.DEFAULT_HIGH_RECALL_MIN,
                    maximum = REVIEW_MAX_RECALL_COUNT,
                ),
            "tag_min_count" to
                reviewIntSchema(
                    default = ReviewSummaryRequest.DEFAULT_TAG_MIN_COUNT,
                    maximum = REVIEW_MAX_TAG_COUNT,
                ),
            "tag_threshold" to boundedReviewNumberSchema(ReviewSummaryRequest.DEFAULT_TAG_THRESHOLD),
            "tag_max_tags" to
                reviewIntSchema(
                    default = ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS,
                    maximum = REVIEW_MAX_TAGS,
                ),
        )

    private fun reviewSummaryHandler(args: JsonNode): Map<String, Any?> {
        val request = requestFrom(args)
        return mapOf("summary" to projectReviewSummary(reviewService.summary(request)))
    }
}

private fun requestFrom(args: JsonNode): ReviewSummaryRequest =
    ReviewSummaryRequest(
        limit = intArg(args, "limit", ReviewSummaryRequest.DEFAULT_LIMIT).coerceIn(1, REVIEW_MAX_LIMIT),
        staleDays =
            intArg(args, "stale_days", ReviewSummaryRequest.DEFAULT_STALE_DAYS)
                .coerceIn(1, REVIEW_MAX_STALE_DAYS),
        lowConfidenceMax =
            doubleArg(
                args,
                "low_confidence_max",
                ReviewSummaryRequest.DEFAULT_LOW_CONFIDENCE_MAX,
            ).coerceIn(0.0, 1.0),
        highRecallMin =
            intArg(args, "high_recall_min", ReviewSummaryRequest.DEFAULT_HIGH_RECALL_MIN)
                .coerceAtLeast(1),
        tagMinCount = intArg(args, "tag_min_count", ReviewSummaryRequest.DEFAULT_TAG_MIN_COUNT).coerceAtLeast(1),
        tagThreshold =
            doubleArg(args, "tag_threshold", ReviewSummaryRequest.DEFAULT_TAG_THRESHOLD)
                .coerceIn(0.0, 1.0),
        tagMaxTags =
            intArg(args, "tag_max_tags", ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS)
                .coerceIn(1, REVIEW_MAX_TAGS),
    )

private fun intArg(
    args: JsonNode,
    name: String,
    default: Int,
): Int = JsonArguments.optionalInt(args, name) ?: default

private fun doubleArg(
    args: JsonNode,
    name: String,
    default: Double,
): Double = JsonArguments.optionalDouble(args, name) ?: default
