package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.beans.factory.annotation.Value
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
 *    jOOQ-codegen mapping. The matching runtime GIN expression index
 *    lives in `db/migration-pg` so jOOQ's H2-backed DDLDatabase never
 *    has to parse it.
 *  - The same `tsquery` expression appears in WHERE + ORDER BY, so
 *    we pass `query` twice as a positional bind to avoid a Postgres
 *    parser re-evaluation.
 */
@Repository
class RecallRepository(
    private val dsl: DSLContext,
    @param:Value("\${knowledge.recall.fts-query-timeout-seconds:5}")
    private val queryTimeoutSeconds: Int,
) {
    fun recall(
        query: String,
        scope: String?,
        limit: Int,
    ): List<RecallHit> {
        if (query.isBlank()) return emptyList()

        val (sql, binds) = recallQuery(query, scope, limit)
        return dsl
            .resultQueryWithBinds(sql, binds)
            .queryTimeout(queryTimeoutSeconds)
            .fetch()
            .map { record -> record.toRecallHit() }
    }

    private fun recallQuery(
        query: String,
        scope: String?,
        limit: Int,
    ): Pair<String, List<Any>> {
        val (scopeClause, scopeBinds) = scopeFilter(scope)
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

        return sql to (listOf(query, query) + scopeBinds + listOf(limit))
    }

    // Scope semantics:
    //   explicit value -> exact-match filter on `kb_notes.scope`.
    //   `all` -> no scope filter; search every scope.
    //   omitted -> curated default: skip `_inbox` and private agent scopes.
    private fun scopeFilter(scope: String?): Pair<String, List<Any>> =
        when {
            scope == null ->
                Pair(
                    "AND scope != '_inbox' " +
                        "AND (scope NOT LIKE 'agent:%' OR scope = 'agent:_shared')",
                    emptyList(),
                )
            scope.equals(SCOPE_ALL, ignoreCase = true) -> Pair("", emptyList())
            else -> Pair("AND scope = ?", listOf(scope))
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

        // Sentinel scope value: callers pass this to opt into a
        // cross-scope query. Anything else is treated as a literal
        // scope filter.
        const val SCOPE_ALL = "all"
    }
}
