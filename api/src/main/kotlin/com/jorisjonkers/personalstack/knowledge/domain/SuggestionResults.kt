package com.jorisjonkers.personalstack.knowledge.domain

/**
 * One topic candidate for `knowledge.suggest_topic`. `slug` carries
 * the bare topic slug (no `topic:` prefix); the caller decides
 * whether to use it for a scope assignment.
 *
 * Score is the cosine similarity between the query embedding and the
 * topic's *centroid* — the mean of every note's embedding in that
 * topic. Out of `[-1, 1]`; > 0.6 typically means a strong match,
 * < 0.3 means "weak, consider creating a new topic".
 */
data class SuggestedTopic(
    val slug: String,
    val score: Double,
    val noteCount: Int,
)

/**
 * One row for `knowledge.find_duplicates`. The score is `1 - cosine_distance`
 * — 1.0 is identical, 0 is orthogonal. Callers filter by an explicit
 * `threshold` parameter (default 0.85) before treating the row as a
 * near-duplicate.
 */
data class DuplicateMatch(
    val id: String,
    val title: String,
    val scope: String,
    val score: Double,
)

/**
 * One cluster of near-duplicate tags surfaced by
 * `knowledge.list_tag_candidates`. Cluster members are tags whose
 * pairwise cosine similarity is at or above the caller's threshold
 * (default 0.85). `suggestedCanonical` is the member with the highest
 * note-count — clusters with low note-counts are typically the
 * stragglers that should fold into the canonical.
 *
 * The operator (or an agent acting on their behalf) feeds the cluster
 * into `knowledge.merge_tags(from=members, into=suggestedCanonical)`
 * — the UI never auto-merges; merges are gated by intent.
 */
data class TagCandidateCluster(
    val members: List<TagCandidateMember>,
    val suggestedCanonical: String,
    val averageSimilarity: Double,
)

data class TagCandidateMember(
    val tag: String,
    val count: Int,
)
