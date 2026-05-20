package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

/**
 * pgvector ANN leg for hybrid recall. Owns the dense-vector half of
 * the read path; the lexical FTS half stays in [RecallRepository] so
 * the two legs can be tested in isolation and fused at the service
 * layer.
 *
 * The `embedding` column lives in the Postgres-only migration tree
 * (`db/migration-pg/V9__embeddings.sql`) — jOOQ's DDLDatabase never
 * sees it, so this repository uses hand-written SQL with the
 * `::vector` cast on the bind parameter (same posture
 * [RecallRepository] takes for `to_tsvector` / `websearch_to_tsquery`).
 *
 * Scope filter semantics mirror [RecallRepository.recall] exactly so
 * the two legs return commensurate hit sets that can be RRF-fused
 * without one leg implicitly broadening the scope.
 */
@Repository
class EmbeddingRepository(
    private val dsl: DSLContext,
) {
    fun recallVector(
        queryEmbedding: FloatArray,
        scope: String?,
        limit: Int,
    ): List<RecallHit> {
        if (queryEmbedding.isEmpty()) return emptyList()

        val (scopeClause, scopeBinds) = scopeFilter(scope)
        val vectorLiteral = queryEmbedding.toPgVectorLiteral()

        // `1 - (embedding <=> ?)` turns the cosine *distance*
        // (0 = identical, 2 = opposite) into a similarity score in
        // [-1, 1]. We never expose a row with NULL embedding — the
        // explicit IS NOT NULL keeps the HNSW index hit even if a
        // future planner regression would otherwise sequential-scan.
        val sql =
            """
            SELECT id, type, scope, title, body,
                   1 - (embedding <=> ?::vector) AS score
            FROM kb_notes
            WHERE embedding IS NOT NULL
              $scopeClause
            ORDER BY embedding <=> ?::vector ASC, id DESC
            LIMIT ?
            """.trimIndent()

        val binds: List<Any> = listOf(vectorLiteral) + scopeBinds + listOf(vectorLiteral, limit)
        return dsl.resultQuery(sql, *binds.toTypedArray()).fetch().map { record -> record.toRecallHit() }
    }

    private fun scopeFilter(scope: String?): Pair<String, List<Any>> =
        when {
            scope == null ->
                Pair(
                    "AND scope != '_inbox' " +
                        "AND (scope NOT LIKE 'agent:%' OR scope = 'agent:_shared')",
                    emptyList(),
                )
            scope.equals(RecallRepository.SCOPE_ALL, ignoreCase = true) -> Pair("", emptyList())
            else -> Pair("AND scope = ?", listOf(scope))
        }

    private fun Record.toRecallHit(): RecallHit {
        val body = get("body", String::class.java) ?: ""
        return RecallHit(
            id = get("id", String::class.java) ?: error("vector row missing id"),
            type = get("type", String::class.java) ?: "",
            scope = get("scope", String::class.java) ?: "",
            title = get("title", String::class.java) ?: "",
            snippet = body.take(SNIPPET_CHARS),
            score = get("score", Double::class.java) ?: 0.0,
        )
    }

    companion object {
        private const val SNIPPET_CHARS = 280

        /**
         * pgvector accepts a `vector` literal as a bracketed
         * comma-separated string: `[0.12, -0.43, ...]`. Bind a
         * pre-rendered string and let the `::vector` cast in SQL do
         * the parse — that keeps us out of pgvector-specific JDBC
         * type adapters (which would either need a third-party
         * driver dependency or hand-rolled `setObject` plumbing).
         */
        private fun FloatArray.toPgVectorLiteral(): String =
            joinToString(separator = ",", prefix = "[", postfix = "]") { f -> f.toString() }
    }
}
