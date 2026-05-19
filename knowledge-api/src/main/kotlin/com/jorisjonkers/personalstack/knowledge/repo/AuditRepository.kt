package com.jorisjonkers.personalstack.knowledge.repo

import com.jorisjonkers.personalstack.knowledge.domain.KbAuditRow
import com.jorisjonkers.personalstack.knowledge.domain.Ulid
import com.jorisjonkers.personalstack.knowledge.jooq.tables.KbAudit.KB_AUDIT
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

/**
 * Read + write access to `kb_audit`. Kept narrow: one append-only
 * insert + a small set of list queries shaped after the most
 * common operator question ("what did actor X do recently?",
 * "what happened to note Y?", "what `<action>` rows fired since
 * `<since>`?").
 *
 * The read path is what backs the `knowledge.list_audit` MCP tool.
 * The write path is consumed by future curator passes (Plan A
 * renormaliser, Plan B reclassifier) and by the topic / tag admin
 * MCP tools — currently those emit structured logs only; flipping
 * them to call `record(...)` is a one-line change per call site.
 */
@Repository
class AuditRepository(
    private val dsl: DSLContext,
) {
    /**
     * Append one audit row. The id is minted server-side as a ULID
     * so writes are atomic from the caller's perspective (no
     * pre-coordination required). Returns the full persisted row
     * including the assigned id + capture time so a caller that
     * wants to log "wrote audit <id>" doesn't need a follow-up
     * SELECT.
     */
    fun record(
        actor: String,
        action: String,
        targetId: String? = null,
        targetKind: String? = null,
        beforeJson: String? = null,
        afterJson: String? = null,
        now: Instant = Instant.now(),
    ): KbAuditRow {
        val id = Ulid.generate()
        val ts = now.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(KB_AUDIT)
            .set(KB_AUDIT.ID, id)
            .set(KB_AUDIT.ACTOR, actor)
            .set(KB_AUDIT.ACTION, action)
            .set(KB_AUDIT.TARGET_ID, targetId)
            .set(KB_AUDIT.TARGET_KIND, targetKind)
            .set(KB_AUDIT.BEFORE_JSON, beforeJson)
            .set(KB_AUDIT.AFTER_JSON, afterJson)
            .set(KB_AUDIT.AT, ts)
            .execute()
        return KbAuditRow(
            id = id,
            actor = actor,
            action = action,
            targetId = targetId,
            targetKind = targetKind,
            beforeJson = beforeJson,
            afterJson = afterJson,
            at = now,
        )
    }

    /**
     * Most recent first. Filters compose: any combination of
     * `actor` / `action` / `targetId` narrows the result set, and
     * `since` clamps to a time window. ULIDs sort lex by time so
     * `ORDER BY id DESC` doubles as recency without an extra column
     * (same trick `list_recent` uses on `kb_notes`).
     */
    fun list(
        actor: String? = null,
        action: String? = null,
        targetId: String? = null,
        since: Instant? = null,
        limit: Int,
    ): List<KbAuditRow> {
        var query =
            dsl
                .selectFrom(KB_AUDIT)
                .where(
                    org.jooq.impl.DSL
                        .noCondition(),
                )
        if (actor != null) query = query.and(KB_AUDIT.ACTOR.eq(actor))
        if (action != null) query = query.and(KB_AUDIT.ACTION.eq(action))
        if (targetId != null) query = query.and(KB_AUDIT.TARGET_ID.eq(targetId))
        if (since != null) {
            val sinceLocal = since.atOffset(ZoneOffset.UTC).toLocalDateTime()
            query = query.and(KB_AUDIT.AT.greaterOrEqual(sinceLocal))
        }
        return query
            .orderBy(KB_AUDIT.ID.desc())
            .limit(limit)
            .fetch()
            .map(::recordToDomain)
    }

    private fun recordToDomain(
        record: com.jorisjonkers.personalstack.knowledge.jooq.tables.records.KbAuditRecord,
    ): KbAuditRow =
        KbAuditRow(
            id = record.id ?: error("kb_audit row missing id"),
            actor = record.actor ?: "",
            action = record.action ?: "",
            targetId = record.targetId,
            targetKind = record.targetKind,
            beforeJson = record.beforeJson,
            afterJson = record.afterJson,
            at = record.at?.toInstant(ZoneOffset.UTC) ?: Instant.EPOCH,
        )
}
