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
