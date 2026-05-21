package com.jorisjonkers.personalstack.knowledge.web

import com.jorisjonkers.personalstack.knowledge.audit.AuditService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Browser-facing REST shim over [AuditService]. Same wire shape as
 * the corresponding MCP tool. The audit log is read-only over this
 * surface; writes happen elsewhere (curator passes, admin MCP tools).
 *
 * `since` accepts ISO-8601 instant strings (e.g.
 * `2026-05-19T07:40:13Z`). A malformed value is ignored so a stale
 * UI control never 400s the page render.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
class KnowledgeAuditController(
    private val auditService: AuditService,
) {
    @GetMapping("/audit")
    fun listAudit(
        @RequestParam("actor", required = false) actor: String?,
        @RequestParam("action", required = false) action: String?,
        @RequestParam("target_id", required = false) targetId: String?,
        @RequestParam("since", required = false) since: String?,
        @RequestParam("limit", defaultValue = "$DEFAULT_AUDIT_LIMIT") limit: Int,
    ): AuditResponse {
        val parsedSince =
            since?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        val rows =
            auditService.list(
                actor = actor,
                action = action,
                targetId = targetId,
                since = parsedSince,
                limit = limit.coerceIn(1, MAX_AUDIT_LIMIT),
            )
        return AuditResponse(rows = rows.map(AuditRowResponse::from))
    }

    companion object {
        private const val DEFAULT_AUDIT_LIMIT = 50
        private const val MAX_AUDIT_LIMIT = 500
    }
}

data class AuditResponse(
    val rows: List<AuditRowResponse>,
)
