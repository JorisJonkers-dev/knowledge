package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNoteTags.KB_NOTE_TAGS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbNotes.KB_NOTES
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbRelations.KB_RELATIONS
import com.jorisjonkers.personalstack.knowledge.jooq.tables.records.KbNotesRecord
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.time.ZoneOffset

@Repository
class NoteRepository(
    private val dsl: DSLContext,
) {
    private val jsonMapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    // -------- write path --------

    fun create(note: KbNote): KbNote {
        val capturedAt = note.capturedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(KB_NOTES)
            .set(KB_NOTES.ID, note.id)
            .set(KB_NOTES.TYPE, note.type.wire)
            .set(KB_NOTES.SCOPE, note.scope)
            .set(KB_NOTES.SOURCE, note.source)
            .set(KB_NOTES.CAPTURED_AT, capturedAt)
            .set(KB_NOTES.SESSION_ID, note.sessionId)
            .set(KB_NOTES.CONFIDENCE, note.confidence.toFloat())
            .set(KB_NOTES.TITLE, note.title)
            .set(KB_NOTES.BODY, note.body)
            .set(KB_NOTES.VAULT_PATH, note.vaultPath)
            .set(KB_NOTES.VAULT_COMMIT, note.vaultCommit)
            .set(KB_NOTES.CREATED_AT, capturedAt)
            .set(KB_NOTES.UPDATED_AT, capturedAt)
            .execute()

        if (note.tags.isNotEmpty()) {
            note.tags.forEach { tag ->
                dsl
                    .insertInto(KB_NOTE_TAGS)
                    .set(KB_NOTE_TAGS.NOTE_ID, note.id)
                    .set(KB_NOTE_TAGS.TAG, tag)
                    .execute()
            }
        }
        return note
    }

    // -------- read path --------

    fun findById(id: String): KbNote? {
        val record =
            dsl
                .selectFrom(KB_NOTES)
                .where(KB_NOTES.ID.eq(id))
                .fetchOne() ?: return null
        return record.toDomain(tagsOf(id))
    }

    fun tagsOf(noteId: String): Set<String> =
        dsl
            .select(KB_NOTE_TAGS.TAG)
            .from(KB_NOTE_TAGS)
            .where(KB_NOTE_TAGS.NOTE_ID.eq(noteId))
            .fetchSet { it.value1() ?: "" }
            .filter { it.isNotBlank() }
            .toSet()

    fun rowCount(): Int = dsl.fetchCount(KB_NOTES)

    /**
     * Recent-first listing scoped optionally by `scope` and `type`.
     * ULIDs sort lex by capture time, so `ORDER BY id DESC` doubles as
     * a recency index without an extra column (see V1 migration head).
     */
    fun listRecent(
        scope: String?,
        type: KbNoteType?,
        limit: Int,
    ): List<KbNote> {
        var query =
            dsl
                .selectFrom(KB_NOTES)
                .where(
                    org.jooq.impl.DSL
                        .noCondition(),
                )
        if (scope != null) query = query.and(KB_NOTES.SCOPE.eq(scope))
        if (type != null) query = query.and(KB_NOTES.TYPE.eq(type.wire))
        val records = query.orderBy(KB_NOTES.ID.desc()).limit(limit).fetch()
        // Bulk-load tags for the returned ids in one query rather than
        // N+1.
        val ids = records.map { it.id ?: "" }.filter { it.isNotBlank() }
        val tagMap = bulkTags(ids)
        return records.map { it.toDomain(tagMap[it.id].orEmpty()) }
    }

    /**
     * Relations either pointing into or out of `id` whose predicate is
     * one of the conflict markers. The MCP `find_conflicts` tool exposes
     * exactly this; the schema keeps room for `derived_from`,
     * `mentions`, etc. but those aren't conflicts.
     */
    fun findConflicts(id: String): List<KbRelation> =
        dsl
            .selectFrom(KB_RELATIONS)
            .where(
                KB_RELATIONS.SUBJECT_ID
                    .eq(id)
                    .or(KB_RELATIONS.OBJECT_ID.eq(id)),
            ).and(KB_RELATIONS.PREDICATE.`in`(CONFLICT_PREDICATES))
            .fetch()
            .map { record ->
                KbRelation(
                    subjectId = record.subjectId ?: "",
                    predicate = record.predicate ?: "",
                    objectId = record.objectId ?: "",
                    props = parseProps(record.props),
                    createdAt =
                        record.createdAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
                )
            }

    /**
     * Walks the relation graph rooted at [id] up to [depth] hops in
     * either direction. Both `subject_id = id OR object_id = id` are
     * traversed so an undirected agent-side view (`see_also`,
     * `contradicts`) renders naturally; `supersedes` and
     * `derived_from` show up alongside, signed by their predicate.
     *
     * Depth 1 returns just the direct neighbours; depth 2 includes
     * their neighbours; etc. The walk dedups visited ids per pass —
     * cycles do not blow up. Capped at [maxDepth] hops + [maxRows]
     * total returned rows so a pathological supersedes-chain cannot
     * exhaust the agent's context.
     */
    fun walkRelations(
        id: String,
        depth: Int,
    ): List<KbRelation> {
        if (depth < 1) return emptyList()
        val effectiveDepth = minOf(depth, MAX_DEPTH)
        val visited = mutableSetOf(id)
        var frontier: Set<String> = setOf(id)
        val collected = mutableListOf<KbRelation>()
        repeat(effectiveDepth) {
            if (frontier.isEmpty()) return@repeat
            val edges = fetchEdgesTouching(frontier)
            frontier = absorbEdges(edges, collected, visited)
            if (collected.size >= MAX_ROWS) return collected
        }
        return collected
    }

    private fun fetchEdgesTouching(ids: Set<String>): List<KbRelation> =
        dsl
            .selectFrom(KB_RELATIONS)
            .where(KB_RELATIONS.SUBJECT_ID.`in`(ids).or(KB_RELATIONS.OBJECT_ID.`in`(ids)))
            .fetch()
            .map { record ->
                KbRelation(
                    subjectId = record.subjectId ?: "",
                    predicate = record.predicate ?: "",
                    objectId = record.objectId ?: "",
                    props = parseProps(record.props),
                    createdAt = record.createdAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
                )
            }

    private fun absorbEdges(
        edges: List<KbRelation>,
        collected: MutableList<KbRelation>,
        visited: MutableSet<String>,
    ): Set<String> {
        val nextFrontier = mutableSetOf<String>()
        for (edge in edges) {
            if (collected.size >= MAX_ROWS) break
            collected += edge
            if (visited.add(edge.subjectId)) nextFrontier += edge.subjectId
            if (visited.add(edge.objectId)) nextFrontier += edge.objectId
        }
        return nextFrontier
    }

    /**
     * Insert helper for tests + the soon-to-arrive `link` tool. Keeps
     * `kb_relations.props` as a JSON-encoded TEXT column (the V1 schema
     * deliberately avoided JSONB to stay inside jOOQ's DDLDatabase
     * subset). Callers must not insert duplicate
     * `(subject_id, predicate, object_id)` tuples — the PK enforces
     * uniqueness and an upsert variant lands when the `link` MCP tool
     * does.
     */
    fun insertRelation(relation: KbRelation) {
        dsl
            .insertInto(KB_RELATIONS)
            .set(KB_RELATIONS.SUBJECT_ID, relation.subjectId)
            .set(KB_RELATIONS.PREDICATE, relation.predicate)
            .set(KB_RELATIONS.OBJECT_ID, relation.objectId)
            .set(KB_RELATIONS.PROPS, jsonMapper.writeValueAsString(relation.props))
            .set(KB_RELATIONS.CREATED_AT, relation.createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime())
            .execute()
    }

    // -------- helpers --------

    private fun bulkTags(ids: List<String>): Map<String, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        return dsl
            .select(KB_NOTE_TAGS.NOTE_ID, KB_NOTE_TAGS.TAG)
            .from(KB_NOTE_TAGS)
            .where(KB_NOTE_TAGS.NOTE_ID.`in`(ids))
            .fetch()
            .groupBy({ it.value1() ?: "" }, { it.value2() ?: "" })
            .mapValues { (_, tags) -> tags.filter { it.isNotBlank() }.toSet() }
    }

    private fun KbNotesRecord.toDomain(tags: Set<String>): KbNote =
        KbNote(
            id = id ?: error("kb_notes row missing id"),
            type = KbNoteType.fromWire(type ?: error("kb_notes row missing type")),
            scope = scope ?: "",
            source = source ?: "",
            capturedAt = capturedAt?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
            sessionId = sessionId,
            confidence = confidence?.toDouble() ?: 0.0,
            title = title ?: "",
            body = body ?: "",
            vaultPath = vaultPath ?: "",
            vaultCommit = vaultCommit,
            tags = tags,
        )

    @Suppress("UNCHECKED_CAST", "SwallowedException")
    private fun parseProps(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            jsonMapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private val CONFLICT_PREDICATES = listOf("supersedes", "contradicts")

        // Bounds for `walkRelations`. The depth cap stops a runaway
        // graph walk; the row cap keeps the agent-facing response
        // from blowing the context budget on a hub note with
        // thousands of incoming edges.
        private const val MAX_DEPTH = 4
        private const val MAX_ROWS = 200
    }
}
