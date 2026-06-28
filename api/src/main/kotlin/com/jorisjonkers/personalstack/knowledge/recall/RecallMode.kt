package com.jorisjonkers.personalstack.knowledge.recall

/**
 * Recall pipeline mode. The four modes map onto the layered design
 * in `_inbox/2026-05-19/075925-knowledge-system-retrieval-architecture…`:
 *
 *  - [FAST]: single-leg Postgres FTS. Phase 4c-2 baseline, ~50 ms p50.
 *  - [HYBRID]: FTS + pgvector ANN, reciprocal-rank-fused. ~100-300 ms
 *    once Ollama is warm. Default once the corpus is fully embedded.
 *  - [DEEP]: HYBRID + optional LightRAG graph/context hit + listwise
 *    rerank. The graph leg is disabled by default and fuses at the hit
 *    level so LightRAG's separate vector space stays isolated.
 *
 * `EXPAND` (HyDE + multi-query) is deliberately not modelled yet —
 * the prior design proposal marks it as diminishing-returns at ≤10³
 * notes and parks it.
 */
enum class RecallMode(
    val wire: String,
) {
    FAST("fast"),
    HYBRID("hybrid"),
    DEEP("deep"),
    ;

    companion object {
        fun fromWire(wire: String?): RecallMode? =
            wire?.let { value ->
                entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
            }
    }
}
