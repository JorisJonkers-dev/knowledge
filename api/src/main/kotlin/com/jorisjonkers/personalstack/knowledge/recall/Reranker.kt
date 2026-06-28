package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit

/**
 * Driven port for the second-stage reranker. Takes a candidate list
 * (typically RRF-fused FTS + vector output) and reorders by an LLM-
 * scored relevance signal that sees query + candidate jointly.
 *
 * Implementations MUST:
 *   - return a strict subset (no insertions of unknown ids) of [candidates]
 *   - never throw on a transient failure — callers expect a clean
 *     fallback to the pre-rerank order. Surface the failure on
 *     telemetry instead.
 *
 * Implementations MAY:
 *   - drop low-scoring tail items beyond `keep`
 *   - preserve original ordering on a failure (the contract is
 *     "best-effort precision uplift").
 */
fun interface Reranker {
    fun rerank(
        query: String,
        candidates: List<RecallHit>,
        keep: Int,
    ): List<RecallHit>
}
