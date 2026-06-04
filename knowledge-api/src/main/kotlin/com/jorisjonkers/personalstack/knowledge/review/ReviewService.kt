package com.jorisjonkers.personalstack.knowledge.review

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import com.jorisjonkers.personalstack.knowledge.discovery.TagClusterService
import com.jorisjonkers.personalstack.knowledge.domain.ReviewBucket
import com.jorisjonkers.personalstack.knowledge.domain.ReviewNote
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSuggestion
import com.jorisjonkers.personalstack.knowledge.domain.ReviewSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagCandidateCluster
import com.jorisjonkers.personalstack.knowledge.repo.ReviewRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val tagClusterService: TagClusterService,
    private val auditService: AuditService,
) {
    fun summary(request: ReviewSummaryRequest = ReviewSummaryRequest()): ReviewSummary {
        val now = Instant.now()
        val noteBuckets = loadNoteBuckets(request, now)
        val tagClusterBucket = loadTagClusters(request)
        val recentAuditRows = auditService.list(limit = request.limit)

        return ReviewSummary(
            generatedAt = now,
            inbox = noteBuckets.inbox,
            needsReview = noteBuckets.needsReview,
            recentAutoCaptures = noteBuckets.recentAutoCaptures,
            staleUnusedNotes = noteBuckets.staleUnusedNotes,
            lowConfidenceHighRecall = noteBuckets.lowConfidenceHighRecall,
            tagCandidateClusters = tagClusterBucket,
            recentAudit = ReviewBucket(total = recentAuditRows.size, items = recentAuditRows),
            suggestions = buildSuggestions(noteBuckets, tagClusterBucket.items),
        )
    }

    private fun loadNoteBuckets(
        request: ReviewSummaryRequest,
        now: Instant,
    ): ReviewNoteBuckets {
        val staleOlderThan = now.minus(Duration.ofDays(request.staleDays.toLong()))
        return ReviewNoteBuckets(
            inbox = reviewRepository.listInbox(request.limit),
            needsReview = reviewRepository.listNeedsReview(request.limit),
            recentAutoCaptures = reviewRepository.listRecentAutoCaptures(request.limit),
            staleUnusedNotes = reviewRepository.listStaleUnusedNotes(staleOlderThan, request.limit),
            lowConfidenceHighRecall =
                reviewRepository.listLowConfidenceHighRecall(
                    maxConfidence = request.lowConfidenceMax,
                    minRecallCount = request.highRecallMin,
                    limit = request.limit,
                ),
        )
    }

    private fun loadTagClusters(request: ReviewSummaryRequest): ReviewBucket<TagCandidateCluster> {
        val clusters =
            tagClusterService
                .listTagCandidates(
                    minCount = request.tagMinCount,
                    threshold = request.tagThreshold,
                    maxTags = request.tagMaxTags,
                )
        return ReviewBucket(total = clusters.size, items = clusters.take(request.limit))
    }

    private fun buildSuggestions(
        buckets: ReviewNoteBuckets,
        tagClusters: List<TagCandidateCluster>,
    ): List<ReviewSuggestion> =
        listOfNotNull(
            needsReviewSuggestion(buckets.needsReview),
            tagMergeSuggestion(tagClusters.firstOrNull()),
            lowConfidenceSuggestion(buckets.lowConfidenceHighRecall.items.firstOrNull()),
            staleSuggestion(buckets.staleUnusedNotes.items.firstOrNull()),
        )

    private fun needsReviewSuggestion(bucket: ReviewBucket<ReviewNote>): ReviewSuggestion? {
        if (bucket.total == 0) return null
        return ReviewSuggestion(
            kind = "needs_review",
            severity = "high",
            message = "${bucket.total} notes are still in `_inbox/_needs-review`.",
            suggestedTool = "knowledge.reclassify_note",
            targetId = bucket.items.firstOrNull()?.id,
            targetKind = "note",
            details = mapOf("total" to bucket.total),
        )
    }

    private fun tagMergeSuggestion(cluster: TagCandidateCluster?): ReviewSuggestion? {
        if (cluster == null) return null
        return ReviewSuggestion(
            kind = "merge_tags",
            severity = "medium",
            message =
                "Review near-duplicate tag cluster ${cluster.members.joinToString { it.tag }} " +
                    "before merging into `${cluster.suggestedCanonical}`.",
            suggestedTool = "knowledge.merge_tags",
            targetId = null,
            targetKind = "tag",
            details =
                mapOf(
                    "suggested_canonical" to cluster.suggestedCanonical,
                    "members" to cluster.members.map { it.tag },
                    "average_similarity" to cluster.averageSimilarity,
                ),
        )
    }

    private fun lowConfidenceSuggestion(note: ReviewNote?): ReviewSuggestion? {
        if (note == null) return null
        return ReviewSuggestion(
            kind = "low_confidence_high_recall",
            severity = "medium",
            message =
                "Frequently recalled low-confidence note `${note.title}` should be " +
                    "reviewed, refined, or superseded.",
            suggestedTool = "knowledge.reclassify_note",
            targetId = note.id,
            targetKind = "note",
            details = mapOf("confidence" to note.confidence, "recall_count" to note.recallCount),
        )
    }

    private fun staleSuggestion(note: ReviewNote?): ReviewSuggestion? {
        if (note == null) return null
        return ReviewSuggestion(
            kind = "stale_unused_note",
            severity = "low",
            message = "Old unrecalled note `${note.title}` may be stale or poorly triggerable.",
            suggestedTool = "knowledge.reclassify_note",
            targetId = note.id,
            targetKind = "note",
            details = mapOf("captured_at" to note.capturedAt.toString(), "vault_path" to note.vaultPath),
        )
    }
}

private data class ReviewNoteBuckets(
    val inbox: ReviewBucket<ReviewNote>,
    val needsReview: ReviewBucket<ReviewNote>,
    val recentAutoCaptures: ReviewBucket<ReviewNote>,
    val staleUnusedNotes: ReviewBucket<ReviewNote>,
    val lowConfidenceHighRecall: ReviewBucket<ReviewNote>,
)

data class ReviewSummaryRequest(
    val limit: Int = DEFAULT_LIMIT,
    val staleDays: Int = DEFAULT_STALE_DAYS,
    val lowConfidenceMax: Double = DEFAULT_LOW_CONFIDENCE_MAX,
    val highRecallMin: Int = DEFAULT_HIGH_RECALL_MIN,
    val tagMinCount: Int = DEFAULT_TAG_MIN_COUNT,
    val tagThreshold: Double = DEFAULT_TAG_THRESHOLD,
    val tagMaxTags: Int = DEFAULT_TAG_MAX_TAGS,
) {
    companion object {
        const val DEFAULT_LIMIT = 10
        const val DEFAULT_STALE_DAYS = 60
        const val DEFAULT_LOW_CONFIDENCE_MAX = 0.6
        const val DEFAULT_HIGH_RECALL_MIN = 3
        const val DEFAULT_TAG_MIN_COUNT = 1
        const val DEFAULT_TAG_THRESHOLD = 0.85
        const val DEFAULT_TAG_MAX_TAGS = 200
    }
}
