@file:Suppress("DEPRECATION") // Jackson 3 deprecated asText()/isTextual.

package com.jorisjonkers.personalstack.knowledge.mcp

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.domain.KbRelation
import com.jorisjonkers.personalstack.knowledge.domain.RecallHit
import com.jorisjonkers.personalstack.knowledge.recall.RecallMode
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Read-path MCP tools: `recall`, `get_note`, `list_recent`,
 * `find_conflicts`, `relations`. The recall tool supports three
 * modes: fast (FTS only), hybrid (FTS + pgvector ANN + RRF), and
 * deep (hybrid + listwise reranker). LightRAG graph recall is a
 * planned follow-up, not the current deep implementation.
 */
@Component
class ReadMcpTools(
    private val recallService: RecallService,
) {
    fun tools(): List<McpTool> =
        listOf(recallTool(), getNoteTool(), listRecentTool(), findConflictsTool(), relationsTool())

    private fun recallTool() = McpTool(recallDescriptor(), ::recallHandler)

    private fun getNoteTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.get_note",
                    description = "Fetch a single note by its ULID. Returns null when no row matches.",
                    required = listOf("id"),
                    properties = mapOf("id" to mapOf("type" to "string")),
                ),
            handler = { args ->
                mapOf(
                    "note" to recallService.getNote(JsonArguments.requireString(args, "id"))?.let(::projectNote),
                )
            },
        )

    private fun listRecentTool() = McpTool(listRecentDescriptor(), ::listRecentHandler)

    private fun findConflictsTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.find_conflicts",
                    description =
                        "Surface kb_relations rows with predicate ∈ {supersedes, contradicts} that " +
                            "touch the given note id (as subject or object).",
                    required = listOf("id"),
                    properties = mapOf("id" to mapOf("type" to "string")),
                ),
            handler = { args ->
                mapOf(
                    "relations" to
                        recallService.findConflicts(JsonArguments.requireString(args, "id")).map(::projectRelation),
                )
            },
        )

    private fun relationsTool() =
        McpTool(
            descriptor =
                toolDescriptor(
                    name = "knowledge.relations",
                    description =
                        "Walk the kb_relations graph rooted at the given note id. Returns every edge " +
                            "(subject_id, predicate, object_id, props) within `depth` hops in either " +
                            "direction — undirected for `see_also` / `contradicts`, directed for " +
                            "`supersedes` / `derived_from` / `refines`. Use this to expand the context " +
                            "around a recall hit before deciding what to read in full.",
                    required = listOf("id"),
                    properties =
                        mapOf(
                            "id" to mapOf("type" to "string"),
                            "depth" to
                                mapOf(
                                    "type" to "integer",
                                    "minimum" to 1,
                                    "maximum" to MAX_RELATION_DEPTH,
                                    "default" to DEFAULT_RELATION_DEPTH,
                                ),
                        ),
                ),
            handler = { args ->
                val id = JsonArguments.requireString(args, "id")
                val depth =
                    JsonArguments.optionalInt(args, "depth") ?: DEFAULT_RELATION_DEPTH
                mapOf("relations" to recallService.walkRelations(id, depth).map(::projectRelation))
            },
        )

    private fun recallDescriptor() =
        toolDescriptor(
            name = "knowledge.recall",
            description = RECALL_TOOL_DESCRIPTION,
            required = listOf("query"),
            properties =
                mapOf(
                    "query" to mapOf("type" to "string"),
                    "mode" to recallModeProperty(),
                    "scope" to recallScopeProperty(),
                    "limit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to MAX_LIMIT,
                            "default" to DEFAULT_RECALL_LIMIT,
                        ),
                ),
        )

    private fun recallModeProperty() =
        mapOf(
            "type" to "string",
            "enum" to RecallMode.entries.map { it.wire },
            "description" to
                "fast = FTS only; hybrid = FTS + vector + RRF; " +
                "deep = hybrid + listwise reranker (LightRAG graph traversal is a planned follow-up).",
        )

    private fun recallScopeProperty() =
        mapOf(
            "type" to "string",
            "description" to
                "Restrict to a single scope (`topic:<slug>` / `project:<repo>` / " +
                "`agent:<name>`). Pass `all` to include every scope including " +
                "untriaged `_inbox`. Omit for the curated default — every " +
                "scope except `_inbox` and assistant-private agent scopes " +
                "(`agent:_shared` stays visible).",
        )

    private fun recallHandler(args: JsonNode): Map<String, Any?> {
        // `query` is "required" by the schema, but a whitespace-only value
        // should produce zero hits, not a 500 — agents sometimes forward
        // an empty prompt verbatim. The repository already short-circuits
        // on blank.
        //
        // `mode` is optional — omit to let the server decide via
        // `knowledge.recall.default-mode`. An unknown wire string falls
        // back to the server default rather than 400ing, because callers
        // shouldn't have to bump their tool definitions in lockstep when
        // a new mode lands (`deep` in a follow-up).
        val resolvedMode =
            JsonArguments.optionalString(args, "mode")?.let(RecallMode::fromWire)
                ?: recallService.defaultMode
        val hits =
            recallService.recall(
                query = JsonArguments.rawText(args, "query").orEmpty(),
                scope = JsonArguments.optionalString(args, "scope"),
                limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_RECALL_LIMIT,
                mode = resolvedMode,
            )
        return mapOf(
            "hits" to hits.map(::projectHit),
            "mode" to resolvedMode.wire,
        )
    }

    private fun listRecentDescriptor() =
        toolDescriptor(
            name = "knowledge.list_recent",
            description =
                "Recent-first listing of kb_notes. ULIDs sort by capture time, so this doubles as a " +
                    "recency index without an extra column.",
            required = emptyList(),
            properties =
                mapOf(
                    "scope" to mapOf("type" to "string"),
                    "type" to
                        mapOf(
                            "type" to "string",
                            "enum" to KbNoteType.entries.map { it.wire },
                        ),
                    "limit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to MAX_LIMIT,
                            "default" to DEFAULT_RECENT_LIMIT,
                        ),
                ),
        )

    private fun listRecentHandler(args: JsonNode): Map<String, Any?> {
        val notes =
            recallService.listRecent(
                scope = JsonArguments.optionalString(args, "scope"),
                type = JsonArguments.optionalString(args, "type")?.let(KbNoteType::fromWire),
                limit = JsonArguments.optionalInt(args, "limit") ?: DEFAULT_RECENT_LIMIT,
            )
        return mapOf("notes" to notes.map(::projectNote))
    }

    // -------- projections --------

    private fun projectNote(note: KbNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type.wire,
            "scope" to note.scope,
            "source" to note.source,
            "captured_at" to note.capturedAt.toString(),
            "session_id" to note.sessionId,
            "confidence" to note.confidence,
            "title" to note.title,
            "body" to note.body,
            "vault_path" to note.vaultPath,
            "vault_commit" to note.vaultCommit,
            "tags" to note.tags.toList().sorted(),
        )

    private fun projectHit(hit: RecallHit): Map<String, Any?> =
        mapOf(
            "id" to hit.id,
            "type" to hit.type,
            "scope" to hit.scope,
            "title" to hit.title,
            "snippet" to hit.snippet,
            "score" to hit.score,
            "tags" to hit.tags.toList().sorted(),
        )

    private fun projectRelation(rel: KbRelation): Map<String, Any?> =
        mapOf(
            "subject_id" to rel.subjectId,
            "predicate" to rel.predicate,
            "object_id" to rel.objectId,
            "props" to rel.props,
            "created_at" to rel.createdAt.toString(),
        )

    companion object {
        private const val DEFAULT_RECALL_LIMIT = 10
        private const val DEFAULT_RECENT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private const val DEFAULT_RELATION_DEPTH = 1

        private const val RECALL_TOOL_DESCRIPTION =
            "Layered recall over kb_notes. `mode=fast` is single-leg Postgres FTS (~50 ms p50). " +
                "`mode=hybrid` adds the pgvector ANN leg and fuses with Reciprocal Rank Fusion " +
                "(~100-300 ms once Ollama is warm). `mode=deep` runs hybrid retrieval and then " +
                "applies a listwise reranker to lift non-obvious candidates — it does not currently " +
                "add LightRAG graph traversal (planned follow-up). Server-side default is " +
                "configurable (`knowledge.recall.default-mode`); when omitted, the server's choice applies."

        // Hard ceiling for the agent-facing depth; the repo enforces
        // its own (private) ceiling underneath, so this just keeps the
        // tool's input shape honest.
        private const val MAX_RELATION_DEPTH = 4
    }
}
