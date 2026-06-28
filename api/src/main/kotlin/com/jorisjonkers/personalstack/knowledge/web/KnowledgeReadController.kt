package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.domain.KbNoteType
import com.jorisjonkers.personalstack.knowledge.recall.RecallMode
import com.jorisjonkers.personalstack.knowledge.recall.RecallService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Browser-facing REST shim over [RecallService]. Same wire shape as
 * the corresponding MCP tools in
 * [com.jorisjonkers.personalstack.knowledge.mcp.ReadMcpTools] — DTOs
 * under [com.jorisjonkers.personalstack.knowledge.web.KnowledgeDtos]
 * project to identical snake_case JSON so a generated TS client and
 * a Claude Code MCP session see the same payload.
 *
 * Auth: `McpBearerFilter.shouldNotFilter` skips this path; production
 * Traefik gates the host with SSO forward-auth (see
 * `access_intent.sso_protected` in fleet.yaml). Cluster-internal +
 * Testcontainers callers reach the endpoints directly.
 *
 * The endpoints intentionally do NOT cover write or admin tools —
 * those stay on the MCP transport and are invoked by an agent-runner
 * Pod on the operator's behalf via knowledge-ui's chat panel.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
class KnowledgeReadController(
    private val recallService: RecallService,
) {
    @GetMapping("/recall")
    fun recall(
        @RequestParam("query") query: String,
        @RequestParam("scope", required = false) scope: String?,
        @RequestParam("limit", defaultValue = "$DEFAULT_RECALL_LIMIT") limit: Int,
        @RequestParam("mode", required = false) modeWire: String?,
    ): RecallResultResponse {
        val mode = RecallMode.fromWire(modeWire) ?: recallService.defaultMode
        val hits =
            recallService.recall(
                query = query,
                scope = scope,
                limit = limit.coerceIn(1, MAX_LIMIT),
                mode = mode,
            )
        return RecallResultResponse(
            mode = mode.wire,
            hits = hits.map(RecallHitResponse::from),
        )
    }

    @GetMapping("/recent")
    fun listRecent(
        @RequestParam("scope", required = false) scope: String?,
        @RequestParam("type", required = false) typeWire: String?,
        @RequestParam("limit", defaultValue = "$DEFAULT_RECENT_LIMIT") limit: Int,
    ): NotesResponse {
        val type = typeWire?.let { KbNoteType.fromWire(it) }
        val notes = recallService.listRecent(scope, type, limit.coerceIn(1, MAX_LIMIT))
        return NotesResponse(notes = notes.map(NoteResponse::from))
    }

    @GetMapping("/notes/{id}")
    fun getNote(
        @PathVariable("id") id: String,
    ): ResponseEntity<NoteResponse> {
        val note = recallService.getNote(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(NoteResponse.from(note))
    }

    @GetMapping("/relations/{id}")
    fun relations(
        @PathVariable("id") id: String,
        @RequestParam("depth", defaultValue = "$DEFAULT_RELATION_DEPTH") depth: Int,
    ): RelationsResponse {
        val rels = recallService.walkRelations(id, depth.coerceIn(1, MAX_RELATION_DEPTH))
        return RelationsResponse(relations = rels.map(RelationResponse::from))
    }

    @GetMapping("/conflicts/{id}")
    fun conflicts(
        @PathVariable("id") id: String,
    ): RelationsResponse {
        val rels = recallService.findConflicts(id)
        return RelationsResponse(relations = rels.map(RelationResponse::from))
    }

    companion object {
        private const val DEFAULT_RECALL_LIMIT = 10
        private const val DEFAULT_RECENT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private const val DEFAULT_RELATION_DEPTH = 1
        private const val MAX_RELATION_DEPTH = 4
    }
}

data class RecallResultResponse(
    val mode: String,
    val hits: List<RecallHitResponse>,
)

data class NotesResponse(
    val notes: List<NoteResponse>,
)

data class RelationsResponse(
    val relations: List<RelationResponse>,
)
