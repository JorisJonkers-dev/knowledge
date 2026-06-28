package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit

/**
 * Reciprocal Rank Fusion (Cormack, Clarke, Buettcher 2009) — the
 * scalar-free way to combine ranked lists with no shared score
 * calibration. Each occurrence of an id at rank `r` (1-based)
 * contributes `1 / (k + r)` to the fused score; the constant `k`
 * dampens the head of the list so a #1 hit on one leg does not
 * dominate a #2-on-both-legs id.
 *
 * Literature default `k = 60`. Surfaced as `knowledge.recall.rrf-k`
 * in application.yml so an operator can tune it from a hot config
 * change rather than a redeploy.
 *
 * Hit metadata: we keep whichever copy of the hit appears *first*
 * (in argument order), preserving the leg-specific snippet/score on
 * the survivor. This is fine because [RecallHit.score] is informational
 * downstream (the MCP response shows it for debug); the rank order
 * is what matters.
 */
internal object ReciprocalRankFusion {
    fun fuse(
        legs: List<List<RecallHit>>,
        k: Int,
        limit: Int,
    ): List<RecallHit> {
        if (legs.all { it.isEmpty() }) return emptyList()

        val fused = LinkedHashMap<String, FusedRow>()
        for (leg in legs) {
            for ((index, hit) in leg.withIndex()) {
                val rank = index + 1
                val score = 1.0 / (k + rank)
                val existing = fused[hit.id]
                if (existing == null) {
                    fused[hit.id] = FusedRow(hit = hit, score = score)
                } else {
                    fused[hit.id] = existing.copy(score = existing.score + score)
                }
            }
        }

        return fused.values
            .sortedWith(compareByDescending<FusedRow> { it.score }.thenByDescending { it.hit.id })
            .take(limit)
            .map { row -> row.hit.copy(score = row.score) }
    }

    private data class FusedRow(
        val hit: RecallHit,
        val score: Double,
    )
}
