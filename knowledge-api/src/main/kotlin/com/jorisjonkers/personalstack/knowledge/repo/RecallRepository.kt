package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

/**
 * Read-side full-text search over `kb_notes`. Phase 4c-2 ships
 * Postgres' built-in `tsvector` / `websearch_to_tsquery` /
 * `ts_rank` — same lexicographic rank model BM25 implementations
 * approximate, no dependency on a worker yet. A follow-up adds the
 * pgvector ANN leg + Ollama embeddings so the two recall halves can
 * be reranked together.
 *
 * The query is hand-written rather than jOOQ-DSL because:
 *  - `to_tsvector(text)` is a Postgres-only function with no
 *    jOOQ-codegen mapping (intentional; the V1 schema avoided GIN
 *    indexes so jOOQ's H2-based DDLDatabase could still parse it).
 *  - The same `tsquery` expression appears in WHERE + ORDER BY, so
 *    we pass `query` twice as a positional bind to avoid a Postgres
 *    parser re-evaluation.
 */
@Repository
class RecallRepository(
    private val dsl: DSLContext,
) {
    fun recall(
        query: String,
        scope: String?,
        limit: Int,
    ): List<RecallHit> {
        if (query.isBlank()) return emptyList()
        val scopeClause = if (scope != null) "AND scope = ?" else ""
        val sql =
            """
            SELECT id, type, scope, title, body,
                   ts_rank(
                     to_tsvector('english', coalesce(title, '') || ' ' || coalesce(body, '')),
                     websearch_to_tsquery('english', ?)
                   ) AS score
            FROM kb_notes
            WHERE to_tsvector('english', coalesce(title, '') || ' ' || coalesce(body, ''))
                  @@ websearch_to_tsquery('english', ?)
            $scopeClause
            ORDER BY score DESC, id DESC
            LIMIT ?
            """.trimIndent()

        val binds: Array<Any> =
            if (scope != null) {
                arrayOf(query, query, scope, limit)
            } else {
                arrayOf(query, query, limit)
            }

        return dsl.resultQuery(sql, *binds).fetch().map { record -> record.toRecallHit() }
    }

    private fun Record.toRecallHit(): RecallHit {
        val body = get("body", String::class.java) ?: ""
        return RecallHit(
            id = get("id", String::class.java) ?: error("recall row missing id"),
            type = get("type", String::class.java) ?: "",
            scope = get("scope", String::class.java) ?: "",
            title = get("title", String::class.java) ?: "",
            snippet = body.take(SNIPPET_CHARS),
            score = get("score", Double::class.java) ?: 0.0,
        )
    }

    companion object {
        private const val SNIPPET_CHARS = 280
    }
}
