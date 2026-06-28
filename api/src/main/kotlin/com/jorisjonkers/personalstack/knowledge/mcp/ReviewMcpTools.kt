package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember
import com.jorisjonkers.personalstack.knowledge.review.ReviewService
import com.jorisjonkers.personalstack.knowledge.review.ReviewSummaryRequest
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
@Suppress("TooManyFunctions") // MCP descriptor, parser, and projection helpers stay co-located.
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
            "limit" to intSchema(default = ReviewSummaryRequest.DEFAULT_LIMIT, maximum = MAX_LIMIT),
            "stale_days" to intSchema(default = ReviewSummaryRequest.DEFAULT_STALE_DAYS, maximum = MAX_STALE_DAYS),
            "low_confidence_max" to boundedNumberSchema(ReviewSummaryRequest.DEFAULT_LOW_CONFIDENCE_MAX),
            "high_recall_min" to
                intSchema(
                    default = ReviewSummaryRequest.DEFAULT_HIGH_RECALL_MIN,
                    maximum = MAX_RECALL_COUNT,
                ),
            "tag_min_count" to intSchema(default = ReviewSummaryRequest.DEFAULT_TAG_MIN_COUNT, maximum = MAX_TAG_COUNT),
            "tag_threshold" to boundedNumberSchema(ReviewSummaryRequest.DEFAULT_TAG_THRESHOLD),
            "tag_max_tags" to intSchema(default = ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS, maximum = MAX_TAGS),
        )

    private fun reviewSummaryHandler(args: JsonNode): Map<String, Any?> {
        val request = requestFrom(args)
        return mapOf("summary" to projectSummary(reviewService.summary(request)))
    }

    private fun requestFrom(args: JsonNode): ReviewSummaryRequest =
        ReviewSummaryRequest(
            limit = intArg(args, "limit", ReviewSummaryRequest.DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
            staleDays = intArg(args, "stale_days", ReviewSummaryRequest.DEFAULT_STALE_DAYS).coerceIn(1, MAX_STALE_DAYS),
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
            tagMaxTags = intArg(args, "tag_max_tags", ReviewSummaryRequest.DEFAULT_TAG_MAX_TAGS).coerceIn(1, MAX_TAGS),
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

    private fun projectSummary(summary: ReviewSummary): Map<String, Any?> =
        mapOf(
            "generated_at" to summary.generatedAt.toString(),
            "inbox" to projectBucket(summary.inbox, ::projectNote),
            "needs_review" to projectBucket(summary.needsReview, ::projectNote),
            "recent_auto_captures" to projectBucket(summary.recentAutoCaptures, ::projectNote),
            "stale_unused_notes" to projectBucket(summary.staleUnusedNotes, ::projectNote),
            "low_confidence_high_recall" to projectBucket(summary.lowConfidenceHighRecall, ::projectNote),
            "tag_candidate_clusters" to projectBucket(summary.tagCandidateClusters, ::projectTagCluster),
            "recent_audit" to projectBucket(summary.recentAudit, ::projectAuditRow),
            "suggestions" to summary.suggestions.map(::projectSuggestion),
        )

    private fun <T> projectBucket(
        bucket: ReviewBucket<T>,
        project: (T) -> Map<String, Any?>,
    ): Map<String, Any?> =
        mapOf(
            "total" to bucket.total,
            "items" to bucket.items.map(project),
        )

    private fun projectNote(note: ReviewNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type,
            "scope" to note.scope,
            "source" to note.source,
            "captured_at" to note.capturedAt.toString(),
            "confidence" to note.confidence,
            "title" to note.title,
            "vault_path" to note.vaultPath,
            "tags" to note.tags.toList().sorted(),
            "recall_count" to note.recallCount,
            "last_recalled_at" to note.lastRecalledAt?.toString(),
        )

    private fun projectTagCluster(cluster: TagCandidateCluster): Map<String, Any?> =
        mapOf(
            "members" to cluster.members.map(::projectTagMember),
            "suggested_canonical" to cluster.suggestedCanonical,
            "average_similarity" to cluster.averageSimilarity,
        )

    private fun projectTagMember(member: TagCandidateMember): Map<String, Any?> =
        mapOf(
            "tag" to member.tag,
            "count" to member.count,
        )

    private fun projectAuditRow(row: KbAuditRow): Map<String, Any?> =
        mapOf(
            "id" to row.id,
            "actor" to row.actor,
            "action" to row.action,
            "target_id" to row.targetId,
            "target_kind" to row.targetKind,
            "before_json" to row.beforeJson,
            "after_json" to row.afterJson,
            "at" to row.at.toString(),
        )

    private fun projectSuggestion(suggestion: ReviewSuggestion): Map<String, Any?> =
        mapOf(
            "kind" to suggestion.kind,
            "severity" to suggestion.severity,
            "message" to suggestion.message,
            "suggested_tool" to suggestion.suggestedTool,
            "target_id" to suggestion.targetId,
            "target_kind" to suggestion.targetKind,
            "details" to suggestion.details,
        )

    private fun intSchema(
        default: Int,
        maximum: Int,
    ): Map<String, Any> =
        mapOf(
            "type" to "integer",
            "minimum" to 1,
            "maximum" to maximum,
            "default" to default,
        )

    private fun boundedNumberSchema(default: Double): Map<String, Any> =
        mapOf(
            "type" to "number",
            "minimum" to 0.0,
            "maximum" to 1.0,
            "default" to default,
        )

    private companion object {
        const val MAX_LIMIT = 50
        const val MAX_STALE_DAYS = 365
        const val MAX_RECALL_COUNT = 1000
        const val MAX_TAG_COUNT = 1000
        const val MAX_TAGS = 500
    }
}
