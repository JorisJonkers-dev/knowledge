package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.DuplicateMatch
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.domain.SuggestedTopic
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.beans.factory.annotation.Value
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
    @param:Value("\${knowledge.recall.vector-query-timeout-seconds:5}")
    private val recallQueryTimeoutSeconds: Int,
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
        return dsl
            .resultQueryWithBinds(sql, binds)
            .queryTimeout(recallQueryTimeoutSeconds)
            .fetch()
            .map { record -> record.toRecallHit() }
    }

    /**
     * `suggest_topic`: for each `topic:<slug>` scope, computes the
     * centroid of its members' embeddings, returns the top-N closest
     * to the query embedding.
     *
     * Postgres' pgvector lets the mean live inside the SQL — no
     * application-side aggregation needed. We pre-compute the
     * centroid per-query rather than caching it; at 10² – 10³ notes
     * the GROUP BY scan is sub-millisecond and a stale cache would
     * silently rot when a new note shifts a topic's centre of mass.
     * Revisit when the corpus crosses 10⁴.
     */
    fun suggestTopic(
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<SuggestedTopic> {
        if (queryEmbedding.isEmpty()) return emptyList()
        val vectorLiteral = queryEmbedding.toPgVectorLiteral()
        val sql =
            """
            SELECT
                substring(scope from 7) AS slug,
                count(*)::int AS note_count,
                1 - (avg(embedding) <=> ?::vector) AS score
            FROM kb_notes
            WHERE scope LIKE 'topic:%'
              AND embedding IS NOT NULL
            GROUP BY scope
            ORDER BY avg(embedding) <=> ?::vector ASC
            LIMIT ?
            """.trimIndent()
        return dsl
            .resultQuery(sql, vectorLiteral, vectorLiteral, limit)
            .fetch()
            .map { record ->
                SuggestedTopic(
                    slug = record.get("slug", String::class.java) ?: "",
                    score = record.get("score", Double::class.java) ?: 0.0,
                    noteCount = record.get("note_count", Int::class.java) ?: 0,
                )
            }
    }

    /**
     * `find_duplicates`: rows whose embedding is within
     * `(1 - threshold)` cosine distance of `queryEmbedding`. Note
     * that `excludeId`, when supplied, lets the tool ignore the
     * source row when looking up its near-neighbours.
     */
    fun findDuplicates(
        queryEmbedding: FloatArray,
        threshold: Double,
        limit: Int,
        excludeId: String? = null,
    ): List<DuplicateMatch> {
        if (queryEmbedding.isEmpty()) return emptyList()
        val (sql, binds) = duplicateQuery(queryEmbedding, threshold, limit, excludeId)
        return dsl
            .resultQueryWithBinds(sql, binds)
            .fetch()
            .map { record ->
                DuplicateMatch(
                    id = record.get("id", String::class.java) ?: "",
                    title = record.get("title", String::class.java) ?: "",
                    scope = record.get("scope", String::class.java) ?: "",
                    score = record.get("score", Double::class.java) ?: 0.0,
                )
            }
    }

    private fun duplicateQuery(
        queryEmbedding: FloatArray,
        threshold: Double,
        limit: Int,
        excludeId: String?,
    ): Pair<String, List<Any>> {
        val vectorLiteral = queryEmbedding.toPgVectorLiteral()
        val maxDistance = (1.0 - threshold).coerceIn(0.0, 2.0)
        val excludeClause = if (excludeId != null) "AND id != ?" else ""
        val sql =
            """
            SELECT id, scope, title,
                   1 - (embedding <=> ?::vector) AS score
            FROM kb_notes
            WHERE embedding IS NOT NULL
              AND (embedding <=> ?::vector) <= ?
              $excludeClause
            ORDER BY embedding <=> ?::vector ASC
            LIMIT ?
            """.trimIndent()
        val binds: List<Any> =
            buildList {
                add(vectorLiteral)
                add(vectorLiteral)
                add(maxDistance)
                if (excludeId != null) add(excludeId)
                add(vectorLiteral)
                add(limit)
            }
        return sql to binds
    }

    /**
     * Fetch a row's persisted embedding so `find_duplicates(id=…)`
     * can use the row's own vector as the query without re-embedding
     * the note. Returns null when the row is missing or hasn't been
     * embedded yet — caller chooses to either fail or re-embed.
     */
    fun embeddingFor(noteId: String): FloatArray? {
        val raw =
            dsl
                .resultQuery(
                    "SELECT embedding::text AS vec FROM kb_notes WHERE id = ? AND embedding IS NOT NULL",
                    noteId,
                ).fetchOne()
                ?.get("vec", String::class.java)
                ?: return null
        return parsePgVectorLiteral(raw)
    }

    /**
     * pgvector's text representation is `[v1,v2,…]`. Strip the
     * brackets, split, parse. Single-statement return so the parse
     * sits comfortably under detekt's `ReturnCount` ceiling.
     */
    private fun parsePgVectorLiteral(raw: String): FloatArray? {
        val inner = raw.trim().removePrefix("[").removeSuffix("]")
        return if (inner.isEmpty()) null else inner.split(",").map { it.trim().toFloat() }.toFloatArray()
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
