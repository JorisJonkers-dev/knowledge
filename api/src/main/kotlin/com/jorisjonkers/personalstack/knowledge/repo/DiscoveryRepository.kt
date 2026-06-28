package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.ScopeSummary
import com.jorisjonkers.personalstack.knowledge.domain.SourceSummary
import com.jorisjonkers.personalstack.knowledge.domain.TagSummary
import com.jorisjonkers.personalstack.knowledge.domain.TopicStats
import com.jorisjonkers.personalstack.knowledge.domain.TopicSummary
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNoteTags.KB_NOTE_TAGS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbTopics.KB_TOPICS
import org.jooq.DSLContext
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.max
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

/**
 * Aggregations over `kb_notes` / `kb_note_tags` that back the
 * discovery-side MCP tools (`list_topics`, `list_tags`,
 * `list_scopes`, `list_sources`, `topic_stats`, `list_inbox`).
 *
 * Kept separate from [NoteRepository] / [RecallRepository] because:
 *   - the access pattern is GROUP BY rather than row-level lookup;
 *   - all queries are read-only, so a future read-replica routing
 *     hook would target this repository specifically;
 *   - the discovery tools are agent-facing aids, not part of the
 *     write path, so the unit-of-work boundary differs.
 *
 * Schema constraints from V1 (see migration head): no JSONB on
 * `kb_relations.props`, no `TEXT[]` on tags (they live in the join
 * table). Aggregations stay inside the jOOQ-DSL surface so the same
 * code compiles against the H2 DDLDatabase the codegen uses.
 */
@Repository
class DiscoveryRepository(
    private val dsl: DSLContext,
) {
    /**
     * Topics in use — derived from `kb_notes.scope LIKE 'topic:%'`,
     * enriched with the description from `kb_topics` when the slug
     * is defined there. Returned with the `topic:` prefix stripped so
     * callers see bare slugs. A slug captured against without first
     * being defined in `kb_topics` still surfaces here; the
     * `description` field is null until an admin adds the row via
     * `add_topic`.
     */
    fun listTopics(limit: Int): List<TopicSummary> {
        val scopeExpr =
            org.jooq.impl.DSL
                .concat(
                    org.jooq.impl.DSL
                        .value(TOPIC_PREFIX),
                    KB_TOPICS.SLUG,
                )
        return dsl
            .select(KB_NOTES.SCOPE, count(), max(KB_NOTES.CAPTURED_AT), KB_TOPICS.DESCRIPTION)
            .from(KB_NOTES)
            .leftJoin(KB_TOPICS)
            .on(scopeExpr.eq(KB_NOTES.SCOPE))
            .where(KB_NOTES.SCOPE.like("$TOPIC_PREFIX%"))
            .groupBy(KB_NOTES.SCOPE, KB_TOPICS.DESCRIPTION)
            .orderBy(count().desc(), KB_NOTES.SCOPE.asc())
            .limit(limit)
            .fetch()
            .map { record ->
                val scope = record.value1() ?: ""
                TopicSummary(
                    slug = scope.removePrefix(TOPIC_PREFIX),
                    noteCount = record.value2() ?: 0,
                    lastCapturedAt = record.value3()?.toInstant(ZoneOffset.UTC),
                    description = record.value4()?.takeIf { it.isNotBlank() },
                )
            }
    }

    /**
     * Tag frequency — tags live in the join table, so the count is a
     * straight GROUP BY. Optional scope filter pushes the predicate
     * onto `kb_notes.scope` so per-project tag drift is observable.
     */
    fun listTags(
        scope: String?,
        limit: Int,
    ): List<TagSummary> {
        val base =
            dsl
                .select(KB_NOTE_TAGS.TAG, count(), max(KB_NOTES.CAPTURED_AT))
                .from(KB_NOTE_TAGS)
                .join(KB_NOTES)
                .on(KB_NOTES.ID.eq(KB_NOTE_TAGS.NOTE_ID))
        val filtered =
            if (scope != null) {
                base.where(KB_NOTES.SCOPE.eq(scope))
            } else {
                base.where(
                    org.jooq.impl.DSL
                        .noCondition(),
                )
            }
        return filtered
            .groupBy(KB_NOTE_TAGS.TAG)
            .orderBy(count().desc(), KB_NOTE_TAGS.TAG.asc())
            .limit(limit)
            .fetch()
            .map { record ->
                TagSummary(
                    tag = record.value1() ?: "",
                    count = record.value2() ?: 0,
                    lastUsedAt = record.value3()?.toInstant(ZoneOffset.UTC),
                )
            }
    }

    /**
     * Every distinct scope in the corpus with its note count + most
     * recent capture. The agent uses this to discover scopes it
     * doesn't already know about — `project:esa-blueshell/website`,
     * `agent:_shared`, etc.
     */
    fun listScopes(limit: Int): List<ScopeSummary> =
        dsl
            .select(KB_NOTES.SCOPE, count(), max(KB_NOTES.CAPTURED_AT))
            .from(KB_NOTES)
            .groupBy(KB_NOTES.SCOPE)
            .orderBy(count().desc(), KB_NOTES.SCOPE.asc())
            .limit(limit)
            .fetch()
            .map { record ->
                ScopeSummary(
                    scope = record.value1() ?: "",
                    noteCount = record.value2() ?: 0,
                    lastCapturedAt = record.value3()?.toInstant(ZoneOffset.UTC),
                )
            }

    /**
     * Every distinct `source` value with its count. Drives
     * provenance audits — "where did this note come from" rolled up.
     */
    fun listSources(limit: Int): List<SourceSummary> =
        dsl
            .select(KB_NOTES.SOURCE, count())
            .from(KB_NOTES)
            .groupBy(KB_NOTES.SOURCE)
            .orderBy(count().desc(), KB_NOTES.SOURCE.asc())
            .limit(limit)
            .fetch()
            .map { record ->
                SourceSummary(
                    source = record.value1() ?: "",
                    count = record.value2() ?: 0,
                )
            }

    /**
     * Per-topic aggregate: counts, capture window, type breakdown,
     * top tags. Returns null when the slug has no notes — the caller
     * surfaces that to the agent rather than fabricating an empty row.
     */
    fun topicStats(
        slug: String,
        topTagLimit: Int,
    ): TopicStats? {
        val scope = "$TOPIC_PREFIX$slug"
        val agg =
            dsl
                .select(
                    count(),
                    org.jooq.impl.DSL
                        .min(KB_NOTES.CAPTURED_AT),
                    max(KB_NOTES.CAPTURED_AT),
                ).from(KB_NOTES)
                .where(KB_NOTES.SCOPE.eq(scope))
                .fetchOne()
        return agg?.let { row ->
            (row.value1() ?: 0).takeIf { it > 0 }?.let { noteCount ->
                TopicStats(
                    slug = slug,
                    noteCount = noteCount,
                    firstCapturedAt = row.value2()?.toInstant(ZoneOffset.UTC),
                    lastCapturedAt = row.value3()?.toInstant(ZoneOffset.UTC),
                    typeBreakdown = typeBreakdownFor(scope),
                    topTags = topTagsFor(scope, topTagLimit),
                )
            }
        }
    }

    private fun typeBreakdownFor(scope: String): Map<String, Int> =
        dsl
            .select(KB_NOTES.TYPE, count())
            .from(KB_NOTES)
            .where(KB_NOTES.SCOPE.eq(scope))
            .groupBy(KB_NOTES.TYPE)
            .fetch()
            .associate { (it.value1() ?: "") to (it.value2() ?: 0) }
            .filterKeys { it.isNotBlank() }

    private fun topTagsFor(
        scope: String,
        limit: Int,
    ): List<TagSummary> =
        dsl
            .select(KB_NOTE_TAGS.TAG, count(), max(KB_NOTES.CAPTURED_AT))
            .from(KB_NOTE_TAGS)
            .join(KB_NOTES)
            .on(KB_NOTES.ID.eq(KB_NOTE_TAGS.NOTE_ID))
            .where(KB_NOTES.SCOPE.eq(scope))
            .groupBy(KB_NOTE_TAGS.TAG)
            .orderBy(count().desc(), KB_NOTE_TAGS.TAG.asc())
            .limit(limit)
            .fetch()
            .map { record ->
                TagSummary(
                    tag = record.value1() ?: "",
                    count = record.value2() ?: 0,
                    lastUsedAt = record.value3()?.toInstant(ZoneOffset.UTC),
                )
            }

    companion object {
        const val TOPIC_PREFIX: String = "topic:"
        const val INBOX_SCOPE: String = "_inbox"
    }
}
