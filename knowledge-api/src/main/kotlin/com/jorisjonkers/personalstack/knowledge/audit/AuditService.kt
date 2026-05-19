package com.jorisjonkers.personalstack.knowledge.audit

import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.repo.AuditRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Read-side façade for the `kb_audit` log. The MCP tool wraps this;
 * future write-side callers (curator passes, admin MCP tools) call
 * [AuditRepository.record] directly rather than route through the
 * service — a service abstraction over an append-only single-table
 * write would be pure ceremony.
 *
 * Kept thin on purpose; complex queries (group-by actor, group-by
 * action) belong in a later observability surface, not in the
 * agent-facing list tool.
 */
@Service
class AuditService(
    private val auditRepository: AuditRepository,
) {
    fun list(
        actor: String? = null,
        action: String? = null,
        targetId: String? = null,
        since: Instant? = null,
        limit: Int,
    ): List<KbAuditRow> =
        auditRepository.list(
            actor = actor,
            action = action,
            targetId = targetId,
            since = since,
            limit = limit,
        )
}
