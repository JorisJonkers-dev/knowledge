package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.repo.EmbeddingRepository
import com.jorisjonkers.personalstack.knowledge.repo.NoteRepository
import com.jorisjonkers.personalstack.knowledge.repo.RecallRepository
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Read-side façade for the MCP read tools: `recall` (FTS), `get_note`,
 * `list_recent`, and `find_conflicts`. Owns mode dispatch:
 *
 *  - [RecallMode.FAST] = single-leg Postgres FTS (Phase 4c-2 baseline).
 *  - [RecallMode.HYBRID] = FTS + pgvector ANN, RRF-fused. Falls back
 *    to FTS-only when the query embedder fails (so a cold Ollama or
 *    a model swap doesn't break recall).
 *  - [RecallMode.DEEP] = HYBRID + listwise rerank. LightRAG mix graph
 *    retrieval plugs in here in a follow-up PR.
 *
 * Every recall call emits a Micrometer Observation with attributes
 * the read-side Grafana panel keys on. Failure of the vector leg is
 * counted in `recall.degraded`; failure of the FTS leg propagates
 * because there is no further fallback.
 */
@Service
class RecallService(
    private val noteRepository: NoteRepository,
    private val recallRepository: RecallRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val queryEmbedder: QueryEmbedder,
    private val reranker: Reranker,
    private val observationRegistry: ObservationRegistry,
    @param:Value("\${knowledge.recall.default-mode:fast}")
    private val defaultModeWire: String,
    @param:Value("\${knowledge.recall.rrf-k:60}")
    private val rrfK: Int,
) {
    private val log = LoggerFactory.getLogger(RecallService::class.java)

    val defaultMode: RecallMode by lazy {
        RecallMode.fromWire(defaultModeWire) ?: RecallMode.FAST
    }

    fun recall(
        query: String,
        scope: String?,
        limit: Int,
        mode: RecallMode = defaultMode,
    ): List<RecallHit> {
        val observation =
            io.micrometer.observation.Observation
                .createNotStarted("knowledge.recall", observationRegistry)
                .lowCardinalityKeyValue("recall.mode", mode.wire)
                .lowCardinalityKeyValue("recall.scope_kind", scopeKind(scope))
        val hits =
            observation.observe<List<RecallHit>> {
                when (mode) {
                    RecallMode.FAST -> recallFast(query, scope, limit, observation)
                    RecallMode.HYBRID -> recallHybrid(query, scope, limit, observation)
                    RecallMode.DEEP -> recallDeep(query, scope, limit, observation)
                }
            }
        bumpRecallStats(hits)
        return hits
    }

    /**
     * Cognee-style usage bump: bookkeeps which rows the operator
     * actually consumes, so the reranker (and future stale-note
     * detection) can layer a usage signal on top of pure semantic
     * similarity.
     *
     * Synchronous + best-effort — the UPDATE is single-statement,
     * indexed-lookup, sub-millisecond at our scale. A failure here
     * never bubbles up; the recall response already serialised.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun bumpRecallStats(hits: List<RecallHit>) {
        if (hits.isEmpty()) return
        try {
            noteRepository.bumpRecallStats(hits.map { it.id })
        } catch (ex: RuntimeException) {
            log.warn("recall: usage-stats bump failed (count={})", hits.size, ex)
        }
    }

    private fun recallDeep(
        query: String,
        scope: String?,
        limit: Int,
        observation: io.micrometer.observation.Observation,
    ): List<RecallHit> {
        // Pull a deeper candidate set so the listwise reranker has tail
        // rows to consider — same shape as the hybrid leg but over-
        // fetched. The reranker truncates back to `limit` before return.
        val fused = recallHybrid(query, scope, limit * RERANK_OVERFETCH_FACTOR, observation)
        if (fused.size <= 1) {
            observation.lowCardinalityKeyValue("recall.rerank_used", "false")
            return fused.take(limit)
        }
        val reranked = reranker.rerank(query, fused, keep = limit)
        observation.lowCardinalityKeyValue("recall.rerank_used", "true")
        observation.highCardinalityKeyValue("recall.reranked_hits", reranked.size.toString())
        return reranked
    }

    fun getNote(id: String): KbNote? = noteRepository.findById(id)

    fun listRecent(
        scope: String?,
        type: KbNoteType?,
        limit: Int,
    ): List<KbNote> = noteRepository.listRecent(scope, type, limit)

    fun findConflicts(id: String): List<KbRelation> = noteRepository.findConflicts(id)

    fun walkRelations(
        id: String,
        depth: Int,
    ): List<KbRelation> = noteRepository.walkRelations(id, depth)

    // -------- mode implementations --------

    private fun recallFast(
        query: String,
        scope: String?,
        limit: Int,
        observation: io.micrometer.observation.Observation,
    ): List<RecallHit> {
        val hits = recallRepository.recall(query, scope, limit)
        observation.highCardinalityKeyValue("recall.fts_hits", hits.size.toString())
        observation.highCardinalityKeyValue("recall.vector_hits", "0")
        return hits
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recallHybrid(
        query: String,
        scope: String?,
        limit: Int,
        observation: io.micrometer.observation.Observation,
    ): List<RecallHit> {
        // The FTS leg is the floor — if it explodes there is nothing to
        // fall back to, so let the exception propagate.
        val ftsHits = recallRepository.recall(query, scope, limit * VECTOR_OVERFETCH_FACTOR)
        observation.highCardinalityKeyValue("recall.fts_hits", ftsHits.size.toString())

        val vectorHits =
            try {
                val embedding = queryEmbedder.embed(query)
                embeddingRepository.recallVector(embedding, scope, limit * VECTOR_OVERFETCH_FACTOR)
            } catch (ex: RuntimeException) {
                // Embedder or vector repo failed — log + count, don't 500
                // the read path. The FTS leg alone still produces an answer.
                // Narrow to RuntimeException so genuine programming errors
                // (Errors, ThreadDeath, …) still propagate; RestClient and
                // JDBC both wrap their failures as RuntimeException subtypes.
                log.warn(
                    "vector leg degraded; falling back to FTS-only (mode=hybrid, scope={}, query.len={})",
                    scope,
                    query.length,
                    ex,
                )
                observation.lowCardinalityKeyValue("recall.degraded", "vector")
                emptyList()
            }
        observation.highCardinalityKeyValue("recall.vector_hits", vectorHits.size.toString())

        // If both legs returned nothing, return nothing — RRF on two
        // empty lists is a wasted allocation, and the absence of hits
        // is itself the signal the caller wants.
        if (ftsHits.isEmpty() && vectorHits.isEmpty()) return emptyList()

        val fused = ReciprocalRankFusion.fuse(listOf(ftsHits, vectorHits), k = rrfK, limit = limit)
        observation.highCardinalityKeyValue("recall.fused_hits", fused.size.toString())
        return fused
    }

    private fun scopeKind(scope: String?): String =
        when {
            scope == null -> "default"
            scope.equals(RecallRepository.SCOPE_ALL, ignoreCase = true) -> "all"
            scope.startsWith("topic:") -> "topic"
            scope.startsWith("project:") -> "project"
            scope.startsWith("agent:") -> "agent"
            else -> "other"
        }

    companion object {
        // Over-fetch from each leg so RRF has tail rows to fuse against;
        // the literature default is 2-3× the user-facing limit. Keep it
        // small enough that the vector index hit stays sub-millisecond.
        private const val VECTOR_OVERFETCH_FACTOR = 3

        // Deep mode adds the reranker pass on top of the RRF-fused
        // hybrid output. Over-fetch by another factor so the reranker
        // can lift a tail item into the top-K — the precision uplift
        // is wasted if every fused candidate is already a "top hit".
        private const val RERANK_OVERFETCH_FACTOR = 2
    }
}
