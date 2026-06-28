package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.domain.DuplicateMatch
import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.SuggestedTopic
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateMember
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary

/**
 * Wire-shape DTOs for the REST controllers under `web/`. Kotlin
 * properties stay camelCase; the snake_case wire form (matching the
 * MCP tools' hand-rolled JSON projections) is produced by Spring's
 * global `spring.jackson.property-naming-strategy: SNAKE_CASE` on
 * the HTTP message converter.
 *
 * Each DTO has a single `from` constructor on the companion that
 * maps from the corresponding domain type. The companion lives on
 * the DTO (rather than an extension function on the domain type)
 * so the domain layer stays unaware of the wire shape.
 *
 * Typed data class DTOs rather than `Map<String, Any?>` so Springdoc
 * produces a typed OpenAPI schema (knowledge-ui's
 * `pnpm contract:generate` round-trips it into TS types).
 */
data class NoteResponse(
    val id: String,
    val type: String,
    val scope: String,
    val source: String,
    val capturedAt: String,
    val sessionId: String?,
    val confidence: Double,
    val title: String,
    val body: String,
    val vaultPath: String,
    val vaultCommit: String?,
    val tags: List<String>,
) {
    companion object {
        fun from(note: KbNote): NoteResponse =
            NoteResponse(
                id = note.id,
                type = note.type.wire,
                scope = note.scope,
                source = note.source,
                capturedAt = note.capturedAt.toString(),
                sessionId = note.sessionId,
                confidence = note.confidence,
                title = note.title,
                body = note.body,
                vaultPath = note.vaultPath,
                vaultCommit = note.vaultCommit,
                tags = note.tags.toList().sorted(),
            )
    }
}

data class RecallHitResponse(
    val id: String,
    val type: String,
    val scope: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val tags: List<String>,
) {
    companion object {
        fun from(hit: RecallHit): RecallHitResponse =
            RecallHitResponse(
                id = hit.id,
                type = hit.type,
                scope = hit.scope,
                title = hit.title,
                snippet = hit.snippet,
                score = hit.score,
                tags = hit.tags.toList().sorted(),
            )
    }
}

data class RelationResponse(
    val subjectId: String,
    val predicate: String,
    val objectId: String,
    val props: Map<String, Any?>,
    val createdAt: String,
) {
    companion object {
        fun from(rel: KbRelation): RelationResponse =
            RelationResponse(
                subjectId = rel.subjectId,
                predicate = rel.predicate,
                objectId = rel.objectId,
                props = rel.props,
                createdAt = rel.createdAt.toString(),
            )
    }
}

data class InboxNoteResponse(
    val id: String,
    val type: String,
    val scope: String,
    val source: String,
    val capturedAt: String,
    val title: String,
    val vaultPath: String,
    val tags: List<String>,
) {
    companion object {
        fun from(note: KbNote): InboxNoteResponse =
            InboxNoteResponse(
                id = note.id,
                type = note.type.wire,
                scope = note.scope,
                source = note.source,
                capturedAt = note.capturedAt.toString(),
                title = note.title,
                vaultPath = note.vaultPath,
                tags = note.tags.toList().sorted(),
            )
    }
}

data class TopicResponse(
    val slug: String,
    val noteCount: Int,
    val lastCapturedAt: String?,
    val description: String?,
) {
    companion object {
        fun from(summary: TopicSummary): TopicResponse =
            TopicResponse(
                slug = summary.slug,
                noteCount = summary.noteCount,
                lastCapturedAt = summary.lastCapturedAt?.toString(),
                description = summary.description,
            )
    }
}

data class TagResponse(
    val tag: String,
    val count: Int,
    val lastUsedAt: String?,
) {
    companion object {
        fun from(summary: TagSummary): TagResponse =
            TagResponse(
                tag = summary.tag,
                count = summary.count,
                lastUsedAt = summary.lastUsedAt?.toString(),
            )
    }
}

data class ScopeResponse(
    val scope: String,
    val noteCount: Int,
    val lastCapturedAt: String?,
) {
    companion object {
        fun from(summary: ScopeSummary): ScopeResponse =
            ScopeResponse(
                scope = summary.scope,
                noteCount = summary.noteCount,
                lastCapturedAt = summary.lastCapturedAt?.toString(),
            )
    }
}

data class SourceResponse(
    val source: String,
    val count: Int,
) {
    companion object {
        fun from(summary: SourceSummary): SourceResponse = SourceResponse(source = summary.source, count = summary.count)
    }
}

data class TopicStatsResponse(
    val slug: String,
    val noteCount: Int,
    val firstCapturedAt: String?,
    val lastCapturedAt: String?,
    val typeBreakdown: Map<String, Int>,
    val topTags: List<TagResponse>,
) {
    companion object {
        fun from(stats: TopicStats): TopicStatsResponse =
            TopicStatsResponse(
                slug = stats.slug,
                noteCount = stats.noteCount,
                firstCapturedAt = stats.firstCapturedAt?.toString(),
                lastCapturedAt = stats.lastCapturedAt?.toString(),
                typeBreakdown = stats.typeBreakdown,
                topTags = stats.topTags.map(TagResponse::from),
            )
    }
}

data class SuggestedTopicResponse(
    val slug: String,
    val score: Double,
    val noteCount: Int,
) {
    companion object {
        fun from(suggestion: SuggestedTopic): SuggestedTopicResponse =
            SuggestedTopicResponse(
                slug = suggestion.slug,
                score = suggestion.score,
                noteCount = suggestion.noteCount,
            )
    }
}

data class DuplicateMatchResponse(
    val id: String,
    val title: String,
    val scope: String,
    val score: Double,
) {
    companion object {
        fun from(match: DuplicateMatch): DuplicateMatchResponse =
            DuplicateMatchResponse(
                id = match.id,
                title = match.title,
                scope = match.scope,
                score = match.score,
            )
    }
}

data class TagCandidateMemberResponse(
    val tag: String,
    val count: Int,
) {
    companion object {
        fun from(member: TagCandidateMember): TagCandidateMemberResponse =
            TagCandidateMemberResponse(tag = member.tag, count = member.count)
    }
}

data class TagCandidateClusterResponse(
    val members: List<TagCandidateMemberResponse>,
    val suggestedCanonical: String,
    val averageSimilarity: Double,
) {
    companion object {
        fun from(cluster: TagCandidateCluster): TagCandidateClusterResponse =
            TagCandidateClusterResponse(
                members = cluster.members.map(TagCandidateMemberResponse::from),
                suggestedCanonical = cluster.suggestedCanonical,
                averageSimilarity = cluster.averageSimilarity,
            )
    }
}

data class AuditRowResponse(
    val id: String,
    val actor: String,
    val action: String,
    val targetId: String?,
    val targetKind: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val at: String,
) {
    companion object {
        fun from(row: KbAuditRow): AuditRowResponse =
            AuditRowResponse(
                id = row.id,
                actor = row.actor,
                action = row.action,
                targetId = row.targetId,
                targetKind = row.targetKind,
                beforeJson = row.beforeJson,
                afterJson = row.afterJson,
                at = row.at.toString(),
            )
    }
}

data class ReviewSummaryResponse(
    val generatedAt: String,
    val inbox: ReviewBucketResponse<ReviewNoteResponse>,
    val needsReview: ReviewBucketResponse<ReviewNoteResponse>,
    val recentAutoCaptures: ReviewBucketResponse<ReviewNoteResponse>,
    val staleUnusedNotes: ReviewBucketResponse<ReviewNoteResponse>,
    val lowConfidenceHighRecall: ReviewBucketResponse<ReviewNoteResponse>,
    val tagCandidateClusters: ReviewBucketResponse<TagCandidateClusterResponse>,
    val recentAudit: ReviewBucketResponse<AuditRowResponse>,
    val suggestions: List<ReviewSuggestionResponse>,
) {
    companion object {
        fun from(summary: ReviewSummary): ReviewSummaryResponse =
            ReviewSummaryResponse(
                generatedAt = summary.generatedAt.toString(),
                inbox = ReviewBucketResponse.from(summary.inbox, ReviewNoteResponse::from),
                needsReview = ReviewBucketResponse.from(summary.needsReview, ReviewNoteResponse::from),
                recentAutoCaptures =
                    ReviewBucketResponse.from(summary.recentAutoCaptures, ReviewNoteResponse::from),
                staleUnusedNotes = ReviewBucketResponse.from(summary.staleUnusedNotes, ReviewNoteResponse::from),
                lowConfidenceHighRecall =
                    ReviewBucketResponse.from(summary.lowConfidenceHighRecall, ReviewNoteResponse::from),
                tagCandidateClusters =
                    ReviewBucketResponse.from(summary.tagCandidateClusters, TagCandidateClusterResponse::from),
                recentAudit = ReviewBucketResponse.from(summary.recentAudit, AuditRowResponse::from),
                suggestions = summary.suggestions.map(ReviewSuggestionResponse::from),
            )
    }
}

data class ReviewBucketResponse<T>(
    val total: Int,
    val items: List<T>,
) {
    companion object {
        fun <T, R> from(
            bucket: ReviewBucket<T>,
            project: (T) -> R,
        ): ReviewBucketResponse<R> = ReviewBucketResponse(total = bucket.total, items = bucket.items.map(project))
    }
}

data class ReviewNoteResponse(
    val id: String,
    val type: String,
    val scope: String,
    val source: String,
    val capturedAt: String,
    val confidence: Double,
    val title: String,
    val vaultPath: String,
    val tags: List<String>,
    val recallCount: Int,
    val lastRecalledAt: String?,
) {
    companion object {
        fun from(note: ReviewNote): ReviewNoteResponse =
            ReviewNoteResponse(
                id = note.id,
                type = note.type,
                scope = note.scope,
                source = note.source,
                capturedAt = note.capturedAt.toString(),
                confidence = note.confidence,
                title = note.title,
                vaultPath = note.vaultPath,
                tags = note.tags.toList().sorted(),
                recallCount = note.recallCount,
                lastRecalledAt = note.lastRecalledAt?.toString(),
            )
    }
}

data class ReviewSuggestionResponse(
    val kind: String,
    val severity: String,
    val message: String,
    val suggestedTool: String?,
    val targetId: String?,
    val targetKind: String?,
    val details: Map<String, Any?>,
) {
    companion object {
        fun from(suggestion: ReviewSuggestion): ReviewSuggestionResponse =
            ReviewSuggestionResponse(
                kind = suggestion.kind,
                severity = suggestion.severity,
                message = suggestion.message,
                suggestedTool = suggestion.suggestedTool,
                targetId = suggestion.targetId,
                targetKind = suggestion.targetKind,
                details = suggestion.details,
            )
    }
}
