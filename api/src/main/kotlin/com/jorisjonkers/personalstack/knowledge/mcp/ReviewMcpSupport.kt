package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember

internal fun projectReviewSummary(summary: ReviewSummary): Map<String, Any?> =
    mapOf(
        "generated_at" to summary.generatedAt.toString(),
        "inbox" to projectReviewBucket(summary.inbox, ::projectReviewNote),
        "needs_review" to projectReviewBucket(summary.needsReview, ::projectReviewNote),
        "recent_auto_captures" to projectReviewBucket(summary.recentAutoCaptures, ::projectReviewNote),
        "stale_unused_notes" to projectReviewBucket(summary.staleUnusedNotes, ::projectReviewNote),
        "low_confidence_high_recall" to projectReviewBucket(summary.lowConfidenceHighRecall, ::projectReviewNote),
        "tag_candidate_clusters" to projectReviewBucket(summary.tagCandidateClusters, ::projectReviewTagCluster),
        "recent_audit" to projectReviewBucket(summary.recentAudit, ::projectReviewAuditRow),
        "suggestions" to summary.suggestions.map(::projectReviewSuggestion),
    )

internal fun <T> projectReviewBucket(
    bucket: ReviewBucket<T>,
    project: (T) -> Map<String, Any?>,
): Map<String, Any?> =
    mapOf(
        "total" to bucket.total,
        "items" to bucket.items.map(project),
    )

internal fun projectReviewNote(note: ReviewNote): Map<String, Any?> =
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

internal fun projectReviewTagCluster(cluster: TagCandidateCluster): Map<String, Any?> =
    mapOf(
        "members" to cluster.members.map(::projectReviewTagMember),
        "suggested_canonical" to cluster.suggestedCanonical,
        "average_similarity" to cluster.averageSimilarity,
    )

internal fun projectReviewTagMember(member: TagCandidateMember): Map<String, Any?> =
    mapOf(
        "tag" to member.tag,
        "count" to member.count,
    )

internal fun projectReviewAuditRow(row: KbAuditRow): Map<String, Any?> =
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

internal fun projectReviewSuggestion(suggestion: ReviewSuggestion): Map<String, Any?> =
    mapOf(
        "kind" to suggestion.kind,
        "severity" to suggestion.severity,
        "message" to suggestion.message,
        "suggested_tool" to suggestion.suggestedTool,
        "target_id" to suggestion.targetId,
        "target_kind" to suggestion.targetKind,
        "details" to suggestion.details,
    )

internal fun reviewIntSchema(
    default: Int,
    maximum: Int,
): Map<String, Any> =
    mapOf(
        "type" to "integer",
        "minimum" to 1,
        "maximum" to maximum,
        "default" to default,
    )

internal fun boundedReviewNumberSchema(default: Double): Map<String, Any> =
    mapOf(
        "type" to "number",
        "minimum" to 0.0,
        "maximum" to 1.0,
        "default" to default,
    )

internal const val REVIEW_MAX_LIMIT = 50
internal const val REVIEW_MAX_STALE_DAYS = 365
internal const val REVIEW_MAX_RECALL_COUNT = 1000
internal const val REVIEW_MAX_TAG_COUNT = 1000
internal const val REVIEW_MAX_TAGS = 500
