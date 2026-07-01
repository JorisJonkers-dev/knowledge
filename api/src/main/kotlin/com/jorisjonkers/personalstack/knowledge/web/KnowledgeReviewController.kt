package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
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
    @Operation(
        parameters = [
            Parameter(
                name = "limit",
                schema = Schema(type = "integer", format = "int32", defaultValue = "10"),
            ),
            Parameter(
                name = "stale_days",
                schema = Schema(type = "integer", format = "int32", defaultValue = "60"),
            ),
            Parameter(
                name = "low_confidence_max",
                schema = Schema(type = "number", format = "double", defaultValue = "0.6"),
            ),
            Parameter(
                name = "high_recall_min",
                schema = Schema(type = "integer", format = "int32", defaultValue = "3"),
            ),
            Parameter(
                name = "tag_min_count",
                schema = Schema(type = "integer", format = "int32", defaultValue = "1"),
            ),
            Parameter(
                name = "tag_threshold",
                schema = Schema(type = "number", format = "double", defaultValue = "0.85"),
            ),
            Parameter(
                name = "tag_max_tags",
                schema = Schema(type = "integer", format = "int32", defaultValue = "200"),
            ),
        ],
    )
    fun summary(request: HttpServletRequest): ReviewSummaryResponse =
        ReviewSummaryResponse.from(
            reviewService.summary(
                ReviewSummaryRequest(
                    limit = intParam(request, "limit", ReviewSummaryRequest.DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
                    staleDays =
                        intParam(request, "stale_days", ReviewSummaryRequest.DEFAULT_STALE_DAYS)
                            .coerceIn(1, MAX_STALE_DAYS),
                    lowConfidenceMax =
                        doubleParam(request, "low_confidence_max", ReviewSummaryRequest.DEFAULT_LOW_CONFIDENCE_MAX)
                            .coerceIn(0.0, 1.0),
                    highRecallMin =
                        intParam(request, "high_recall_min", ReviewSummaryRequest.DEFAULT_HIGH_RECALL_MIN)
                            .coerceAtLeast(1),
                    tagMinCount =
                        intParam(request, "tag_min_count", ReviewSummaryRequest.DEFAULT_TAG_MIN_COUNT)
                            .coerceAtLeast(1),
                    tagThreshold =
                        doubleParam(request, "tag_threshold", ReviewSummaryRequest.DEFAULT_TAG_THRESHOLD)
                            .coerceIn(0.0, 1.0),
                    tagMaxTags =
                        intParam(request, "tag_max_tags", ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS)
                            .coerceIn(1, MAX_TAGS),
                ),
            ),
        )

    private fun intParam(
        request: HttpServletRequest,
        name: String,
        default: Int,
    ): Int = request.getParameter(name)?.toIntOrNull() ?: default

    private fun doubleParam(
        request: HttpServletRequest,
        name: String,
        default: Double,
    ): Double = request.getParameter(name)?.toDoubleOrNull() ?: default

    private companion object {
        const val MAX_LIMIT = 50
        const val MAX_STALE_DAYS = 365
        const val MAX_TAGS = 500
    }
}
