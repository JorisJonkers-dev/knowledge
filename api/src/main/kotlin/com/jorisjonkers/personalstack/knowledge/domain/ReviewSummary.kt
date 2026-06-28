package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

data class ReviewBucket<T>(
    val total: Int,
    val items: List<T>,
)

data class ReviewSummary(
    val generatedAt: Instant,
    val inbox: ReviewBucket<ReviewNote>,
    val needsReview: ReviewBucket<ReviewNote>,
    val recentAutoCaptures: ReviewBucket<ReviewNote>,
    val staleUnusedNotes: ReviewBucket<ReviewNote>,
    val lowConfidenceHighRecall: ReviewBucket<ReviewNote>,
    val tagCandidateClusters: ReviewBucket<TagCandidateCluster>,
    val recentAudit: ReviewBucket<KbAuditRow>,
    val suggestions: List<ReviewSuggestion>,
)

data class ReviewNote(
    val id: String,
    val type: String,
    val scope: String,
    val source: String,
    val capturedAt: Instant,
    val confidence: Double,
    val title: String,
    val vaultPath: String,
    val tags: Set<String> = emptySet(),
    val recallCount: Int,
    val lastRecalledAt: Instant?,
)

data class ReviewSuggestion(
    val kind: String,
    val severity: String,
    val message: String,
    val suggestedTool: String?,
    val targetId: String?,
    val targetKind: String?,
    val details: Map<String, Any?> = emptyMap(),
)
